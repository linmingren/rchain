package coop.rchain.rosette

import com.typesafe.scalalogging.Logger
import coop.rchain.rosette.Ob._
import coop.rchain.rosette.prim.Prim

sealed trait RblError
case object DeadThread extends RblError
case object Invalid extends RblError
case object Suspend extends RblError
case object Absent extends RblError
case object Upcall extends RblError
case class PrimMismatch(msg: String) extends RblError
case class RuntimeError(msg: String) extends RblError

sealed trait Work
case object NoWorkLeft extends Work
case object WaitForAsync extends Work
case class StrandsScheduled(state: VMState) extends Work

object VirtualMachine {

  val loggerOpcode = Logger("opcode")
  val loggerStrand = Logger("strand")

  val vmLiterals: Seq[Ob] = Seq(
    Fixnum(0),
    Fixnum(1),
    Fixnum(2),
    Fixnum(3),
    Fixnum(4),
    Fixnum(5),
    Fixnum(6),
    Fixnum(7),
    Ob.RBLTRUE,
    Ob.RBLFALSE,
    Tuple.NIL,
    Ob.NIV
  )

  def handleApplyPrimSuspend(op: Op): Unit = ()
  def handleApplyPrimUpcall(op: Op, tag: Location): Unit = ()
  def handleFormalsMismatch(formals: Template): Ob = null
  def handleMissingBinding(key: Ob, argReg: Location): Ob = null
  def handleSleep(): Unit = ()
  def handleXmitUpcall(op: Op, tag: Location): Unit = ()

  def handleVirtualMachineError(state: VMState): VMState =
    state.ctxt.vmError(state)._2

  /**
    *  This code protects the current argvec, temporarily replacing it
    *  with the unwound argvec for use by the primitive, and then
    *  restoring it after the primitive has finished.  This is necessary
    *  because of the way that the compiler permits inlined primitives
    *  (the subjects of opApplyPrim opcodes) to share a common argvec.
    *  Unwinding cannot be permitted to clobber the argvec that the
    *  compiler has set up, or bad things can happen (and they are *hard*
    *  to track down).
    */
  def unwindAndApplyPrim(prim: Prim, state: VMState): (Result, VMState) =
    state.ctxt.argvec.flattenRest() match {
      case Right(newArgvec) =>
        val tmpState = state
          .set(_ >> 'ctxt >> 'argvec)(newArgvec)
          .set(_ >> 'ctxt >> 'nargs)(newArgvec.elem.size)

        val result = prim.dispatchHelper(tmpState.ctxt)

        (result, state)

      case Left(AbsentRest) =>
        val tmpState = state
          .set(_ >> 'ctxt >> 'argvec)(Tuple.NIL)
          .set(_ >> 'ctxt >> 'nargs)(0)

        val result = prim.dispatchHelper(tmpState.ctxt)

        (result, state)

      case Left(InvalidRest) =>
        val (error, errorState) =
          prim.runtimeError("&rest value is not a tuple", state)

        (Left(error), errorState)
    }

  def handleException(v: Ob, op: Op, tag: Location): Unit =
    v.sysval match {
      case SyscodeUpcall =>
        op match {
          case _: OpApplyCmd | _: OpApplyPrimArg | _: OpApplyPrimReg |
              _: OpApplyPrimTag =>
            handleApplyPrimUpcall(op, tag)
          case _ =>
            handleXmitUpcall(op, tag)
        }

      case SyscodeSuspend =>
        op match {
          case _: OpApplyCmd | _: OpApplyPrimArg | _: OpApplyPrimReg |
              _: OpApplyPrimTag =>
            handleApplyPrimSuspend(op)

          case _ => // Nothing happens; this is the usual case.
        }

      case SyscodeInterrupt => suicide("what to do with syscodeInterrupt?")

      case SyscodeSleep => handleSleep()

      case SyscodeInvalid | SyscodeDeadThread => // We don't do diddly

      case _ => suicide(s"unknown SysCode value (${v.sysval})")
    }

  def getNextStrand(state: VMState): (Boolean, VMState) = {
    loggerStrand.info("Try to get next strand")

    if (state.strandPool.isEmpty) {
      tryAwakeSleepingStrand(state) match {
        case WaitForAsync =>
          val newState = state.set(_ >> 'doAsyncWaitFlag)(true)
          (false, newState)

        case NoWorkLeft => (true, state)

        case StrandsScheduled(stateScheduled) =>
          val stateDebug = if (stateScheduled.debug) {
            state.update(_ >> 'debugInfo)(_ :+ "*** waking sleepers\n")
          } else {
            stateScheduled
          }

          val strand = stateDebug.strandPool.head
          val newState = stateDebug.update(_ >> 'strandPool)(_.tail)

          (false, installStrand(strand, newState))
      }
    } else {
      val strand = state.strandPool.head
      val newState = state.update(_ >> 'strandPool)(_.tail)

      (false, installStrand(strand, newState))
    }
  }

  def tryAwakeSleepingStrand(state: VMState): Work =
    if (state.sleeperPool.isEmpty) {
      if (state.nsigs == 0) {
        NoWorkLeft
      } else {
        WaitForAsync
      }
    } else {

      /** Schedule all sleeping strands
        *
        * Pop strand from sleeperPool and enqueue
        * to strandPool
        */
      val scheduled = state.sleeperPool
        .foldLeft(state) {
          case (st, sleeper) =>
            sleeper.scheduleStrand(st)
        }
        .set(_ >> 'sleeperPool)(Seq())

      StrandsScheduled(scheduled)
    }

  def installStrand(strand: Ctxt, state: VMState): VMState = {
    val stateInstallMonitor =
      if (strand.monitor != state.currentMonitor)
        installMonitor(strand.monitor, state)
      else state

    loggerStrand.info(s"Install strand ${strand.hashCode()}")
    installCtxt(strand, stateInstallMonitor)
  }

  def installMonitor(monitor: Monitor, state: VMState): VMState = {
    val stateDebug = if (state.debug) {
      state.update(_ >> 'debugInfo)(_ :+ s"*** new monitor: ${monitor.id}\n")
    } else {
      state
    }

    stateDebug.currentMonitor.stop()

    val newState = stateDebug
      .set(_ >> 'bytecodes)(monitor.opcodeCounts)
      .set(_ >> 'currentMonitor)(monitor)
      .set(_ >> 'debug)(monitor.tracing)
      .set(_ >> 'obCounts)(monitor.obCounts)

    newState.currentMonitor
      .start()

    newState
  }

  def installCtxt(ctxt: Ctxt, state: VMState): VMState = {
    val stateDebug = if (state.debug) {
      state.update(_ >> 'debugInfo)(_ :+ "*** new strand\n")
    } else {
      state
    }

    stateDebug
      .set(_ >> 'ctxt)(ctxt)
      .set(_ >> 'code)(ctxt.code)
      .set(_ >> 'pc >> 'relative)(ctxt.pc.relative)
  }

  def executeSeq(opCodes: Seq[Op], state: VMState): VMState = {
    var pc = state.pc.relative
    var exit = false
    var currentState = state

    while (pc < opCodes.size && !exit) {
      val op = opCodes(pc)
      loggerOpcode.info("PC: " + pc + " Opcode: " + op)

      currentState = currentState
        .update(_ >> 'pc >> 'relative)(_ + 1)
        .update(_ >> 'bytecodes)(
          _.updated(op, currentState.bytecodes.getOrElse(op, 0.toLong) + 1))

      currentState = runFlags(executeDispatch(op, currentState))

      pc = currentState.pc.relative

      if (currentState.exitFlag) exit = true
    }

    currentState
  }

  def runFlags(state: VMState): VMState = {
    var mState = state

    if (mState.doXmitFlag) {
      // may set doNextThreadFlag
      mState = doXmit(mState)
    }

    if (mState.doRtnFlag) {
      // may set doNextThreadFlag
      mState = doRtn(mState).set(_ >> 'doRtnFlag)(false)
    }

    if (mState.vmErrorFlag) {
      // TODO: Revisit once OprnVmError works
      //handleVirtualMachineError(mState)
      mState = mState.set(_ >> 'doNextThreadFlag)(true)
    }

    if (mState.doNextThreadFlag) {
      val (isEmpty, newState) = getNextStrand(mState)
      mState = newState.set(_ >> 'doNextThreadFlag)(false)

      if (isEmpty) {
        mState = mState.set(_ >> 'exitFlag)(true)
      }
    }

    mState
  }

  def doRtn(state: VMState): VMState = {
    val (isError, newState) = state.ctxt.ret(state.ctxt.rslt)(state)

    if (isError)
      newState.set(_ >> 'vmErrorFlag)(true)
    else if (newState.doRtnFlag)
      newState.set(_ >> 'doNextThreadFlag)(true)
    else
      newState
  }

  def doXmit(state: VMState): VMState =
    state.ctxt.trgt match {
      case ob: StdOprn => ob.dispatch(state)._2

      // TODO: Add other cases
      case _ => state
    }

  def executeDispatch(op: Op, state: VMState): VMState =
    op match {
      case o: OpHalt => execute(o, state)
      case o: OpPush => execute(o, state)
      case o: OpPop => execute(o, state)
      case o: OpNargs => execute(o, state)
      case o: OpPushAlloc => execute(o, state)
      case o: OpExtend => execute(o, state)
      case o: OpOutstanding => execute(o, state)
      case o: OpAlloc => execute(o, state)
      case o: OpFork => execute(o, state)
      case o: OpXmitTag => execute(o, state)
      case o: OpXmitArg => execute(o, state)
      case o: OpXmitReg => execute(o, state)
      case o: OpXmit => execute(o, state)
      case o: OpXmitTagXtnd => execute(o, state)
      case o: OpXmitArgXtnd => execute(o, state)
      case o: OpXmitRegXtnd => execute(o, state)
      case o: OpSend => execute(o, state)
      case o: OpApplyPrimTag => execute(o, state)
      case o: OpApplyPrimArg => execute(o, state)
      case o: OpApplyPrimReg => execute(o, state)
      case o: OpApplyCmd => execute(o, state)
      case o: OpRtnTag => execute(o, state)
      case o: OpRtnArg => execute(o, state)
      case o: OpRtnReg => execute(o, state)
      case o: OpRtn => execute(o, state)
      case o: OpUpcallRtn => execute(o, state)
      case o: OpUpcallResume => execute(o, state)
      case o: OpNxt => execute(o, state)
      case o: OpJmp => execute(o, state)
      case o: OpJmpFalse => execute(o, state)
      case o: OpJmpCut => execute(o, state)
      case o: OpLookupToArg => execute(o, state)
      case o: OpLookupToReg => execute(o, state)
      case o: OpXferLexToArg => execute(o, state)
      case o: OpXferLexToReg => execute(o, state)
      case o: OpXferGlobalToArg => execute(o, state)
      case o: OpXferGlobalToReg => execute(o, state)
      case o: OpXferArgToArg => execute(o, state)
      case o: OpXferRsltToArg => execute(o, state)
      case o: OpXferArgToRslt => execute(o, state)
      case o: OpXferRsltToReg => execute(o, state)
      case o: OpXferRegToRslt => execute(o, state)
      case o: OpXferRsltToDest => execute(o, state)
      case o: OpXferSrcToRslt => execute(o, state)
      case o: OpIndLitToArg => execute(o, state)
      case o: OpIndLitToReg => execute(o, state)
      case o: OpIndLitToRslt => execute(o, state)
      case o: OpImmediateLitToArg => execute(o, state)
      case o: OpImmediateLitToReg => execute(o, state)
      case o: OpUnknown => execute(o, state)
    }

  def execute(op: OpHalt, state: VMState): VMState =
    state.set(_ >> 'exitFlag)(true).set(_ >> 'exitCode)(0)

  def execute(op: OpPush, state: VMState): VMState =
    state.set(_ >> 'ctxt)(Ctxt(None, state.ctxt))

  def execute(op: OpPop, state: VMState): VMState =
    state.set(_ >> 'ctxt)(state.ctxt.ctxt)

  def execute(op: OpNargs, state: VMState): VMState =
    state.set(_ >> 'ctxt >> 'nargs)(op.n)

  def execute(op: OpAlloc, state: VMState): VMState =
    state.set(_ >> 'ctxt >> 'argvec)(Tuple(op.n, NIV))

  def execute(op: OpPushAlloc, state: VMState): VMState =
    state.update(_ >> 'ctxt)(Ctxt(Some(Tuple(op.n, None)), _))

  def execute(op: OpExtend, state: VMState): VMState = {
    val formals = state.code.lit(op.v).asInstanceOf[Template]
    val actuals = formals.matchPattern(state.ctxt.argvec, state.ctxt.nargs)

    actuals match {
      case Some(tuple) =>
        state
          .set(_ >> 'ctxt >> 'nargs)(0)
          .set(_ >> 'ctxt >> 'env)(
            state.ctxt.env.extendWith(formals.keymeta, tuple))

      case None =>
        handleFormalsMismatch(formals)
        state.set(_ >> 'doNextThreadFlag)(true)
    }
  }

  def execute(op: OpOutstanding, state: VMState): VMState =
    state
      .set(_ >> 'ctxt >> 'pc)(PC.fromInt(op.p))
      .set(_ >> 'ctxt >> 'outstanding)(op.n)

  def execute(op: OpFork, state: VMState): VMState =
    state.set(_ >> 'strandPool)(
      state.ctxt.copy(pc = PC.fromInt(op.p)) +: state.strandPool)

  def execute(op: OpXmitTag, state: VMState): VMState =
    state
      .set(_ >> 'ctxt >> 'nargs)(op.m)
      .set(_ >> 'ctxt >> 'tag)(LocationAtom(state.code.lit(op.v)))
      .set(_ >> 'xmitData)((op.u, op.n))
      .set(_ >> 'doXmitFlag)(true)

  def execute(op: OpXmitArg, state: VMState): VMState =
    state
      .set(_ >> 'ctxt >> 'nargs)(op.m)
      .set(_ >> 'ctxt >> 'tag)(Location.ArgReg(op.a))
      .set(_ >> 'xmitData)((op.u, op.n))
      .set(_ >> 'doXmitFlag)(true)

  def execute(op: OpXmitReg, state: VMState): VMState =
    state
      .set(_ >> 'ctxt >> 'nargs)(op.m)
      .set(_ >> 'ctxt >> 'tag)(Location.CtxtReg(op.r))
      .set(_ >> 'xmitData)((op.u, op.n))
      .set(_ >> 'doXmitFlag)(true)

  def execute(op: OpXmit, state: VMState): VMState =
    state
      .set(_ >> 'ctxt >> 'nargs)(op.m)
      .set(_ >> 'xmitData)((op.u, op.n))
      .set(_ >> 'doXmitFlag)(true)

  def execute(op: OpXmitTagXtnd, state: VMState): VMState =
    state
      .set(_ >> 'ctxt >> 'nargs)(op.m)
      .set(_ >> 'ctxt >> 'tag)(LocationAtom(state.code.lit(op.v)))
      .set(_ >> 'xmitData)((op.u, op.n))
      .set(_ >> 'doXmitFlag)(true)

  def execute(op: OpXmitArgXtnd, state: VMState): VMState =
    state
      .set(_ >> 'ctxt >> 'nargs)(op.m)
      .set(_ >> 'ctxt >> 'tag)(Location.ArgReg(op.a))
      .set(_ >> 'xmitData)((op.u, op.n))
      .set(_ >> 'doXmitFlag)(true)

  def execute(op: OpXmitRegXtnd, state: VMState): VMState =
    state
      .set(_ >> 'ctxt >> 'nargs)(op.m)
      .set(_ >> 'ctxt >> 'tag)(Location.CtxtReg(op.r))
      .set(_ >> 'xmitData)((op.u, op.n))
      .set(_ >> 'doXmitFlag)(true)

  def execute(op: OpSend, state: VMState): VMState =
    state
      .set(_ >> 'ctxt >> 'ctxt)(Ctxt.NIV)
      .set(_ >> 'ctxt >> 'nargs)(op.m)
      .set(_ >> 'xmitData)((op.u, op.n))
      .set(_ >> 'doXmitFlag)(true)

  def execute(op: OpApplyPrimTag, state: VMState): VMState =
    state
      .set(_ >> 'ctxt >> 'nargs)(op.m)
      .set(_ >> 'loc)(LocationAtom(state.code.lit(op.v)))
      .updateSelf(state => {
        val prim = Prim.nthPrim(op.k)

        val (result, newState) =
          // TODO: Remove get
          if (op.u) { unwindAndApplyPrim(prim.get, state) } else {
            // TODO: Fix
            (prim.get.dispatchHelper(state.ctxt), state)
          }

        result match {
          case Right(ob) =>
            if (ob.is(Ob.OTsysval)) {
              handleException(ob, op, newState.loc)
              newState.set(_ >> 'doNextThreadFlag)(true)
            } else {
              import Location._

              Location
                .store(newState.loc, newState.ctxt, newState.globalEnv, ob) match {
                case StoreFail => newState.set(_ >> 'vmErrorFlag)(true)

                case StoreCtxt(ctxt) =>
                  newState
                    .set(_ >> 'ctxt)(ctxt)
                    .update(_ >> 'doNextThreadFlag)(if (op.n) true else _)

                case StoreGlobal(env) => newState.set(_ >> 'globalEnv)(env)
              }
            }

          case Left(DeadThread) =>
            newState.set(_ >> 'doNextThreadFlag)(true)
        }
      })

  def execute(op: OpApplyPrimArg, state: VMState): VMState =
    state
      .set(_ >> 'ctxt >> 'nargs)(op.m)
      .updateSelf(state => {
        val prim = Prim.nthPrim(op.k)
        val argno = op.a

        val (result, newState) =
          if (op.u) { unwindAndApplyPrim(prim.get, state) } else {
            // TODO: Fix
            (prim.get.dispatchHelper(state.ctxt), state)
          }

        result match {
          case Right(ob) =>
            if (ob.is(Ob.OTsysval)) {
              handleException(ob, op, newState.loc)
              newState.set(_ >> 'doNextThreadFlag)(true)
            } else if (argno >= newState.ctxt.argvec.elem.length) {
              newState.set(_ >> 'vmErrorFlag)(true)
            } else {
              newState
                .update(_ >> 'ctxt >> 'argvec >> 'elem)(_.updated(argno, ob))
                .update(_ >> 'doNextThreadFlag)(if (op.n) true else _)
            }

          case Left(DeadThread) =>
            newState.set(_ >> 'doNextThreadFlag)(true)
        }

      })

  def execute(op: OpApplyPrimReg, state: VMState): VMState =
    state
      .set(_ >> 'ctxt >> 'nargs)(op.m)
      .updateSelf(state => {
        val prim = Prim.nthPrim(op.k)
        val regno = op.r

        val (result, newState) =
          if (op.u) { unwindAndApplyPrim(prim.get, state) } else {
            // TODO: Fix
            (prim.get.dispatchHelper(state.ctxt), state)
          }

        result match {
          case Right(ob) =>
            if (ob.is(Ob.OTsysval)) {
              handleException(ob, op, Location.CtxtReg(regno))
              newState.set(_ >> 'doNextThreadFlag)(true)
            } else {
              setCtxtReg(regno, ob)(newState)
                .update(_ >> 'doNextThreadFlag)(if (op.n) true else _)
            }

          case Left(DeadThread) =>
            newState.set(_ >> 'doNextThreadFlag)(true)
        }

      })

  def execute(op: OpApplyCmd, state: VMState): VMState =
    state
      .set(_ >> 'ctxt >> 'nargs)(op.m)
      .updateSelf(state => {
        val prim = Prim.nthPrim(op.k)

        val (result, newState) =
          if (op.u) { unwindAndApplyPrim(prim.get, state) } else {
            // TODO: Fix
            (prim.get.dispatchHelper(state.ctxt), state)
          }

        result match {
          case Right(ob) =>
            if (ob.is(Ob.OTsysval)) {
              handleException(ob, op, Location.LIMBO)
              newState.set(_ >> 'doNextThreadFlag)(true)
            } else {
              newState.update(_ >> 'doNextThreadFlag)(if (op.n) true else _)
            }
          case Left(DeadThread) =>
            newState.set(_ >> 'doNextThreadFlag)(true)
        }
      })

  def execute(op: OpRtn, state: VMState): VMState =
    state
      .set(_ >> 'doRtnData)(op.n)
      .set(_ >> 'doRtnFlag)(true)

  def execute(op: OpRtnTag, state: VMState): VMState =
    state
      .set(_ >> 'ctxt >> 'tag)(LocationAtom(state.code.lit(op.v)))
      .set(_ >> 'doRtnData)(op.n)
      .set(_ >> 'doRtnFlag)(true)

  def execute(op: OpRtnArg, state: VMState): VMState =
    state
      .set(_ >> 'ctxt >> 'tag)(Location.ArgReg(op.a))
      .set(_ >> 'doRtnData)(op.n)
      .set(_ >> 'doRtnFlag)(true)

  def execute(op: OpRtnReg, state: VMState): VMState =
    state
      .set(_ >> 'ctxt >> 'tag)(Location.CtxtReg(op.r))
      .set(_ >> 'doRtnData)(op.n)
      .set(_ >> 'doRtnFlag)(true)

  def execute(op: OpUpcallRtn, state: VMState): VMState =
    state
      .set(_ >> 'ctxt >> 'tag)(LocationAtom(state.code.lit(op.v)))
      .updateSelf(state => {
        val ctxt = state.ctxt

        import Location._

        Location.store(ctxt.tag, ctxt.ctxt, state.globalEnv, ctxt.rslt) match {
          case StoreFail => state.set(_ >> 'vmErrorFlag)(true)

          case StoreCtxt(ctxt) =>
            state
              .set(_ >> 'ctxt)(ctxt)
              .update(_ >> 'doNextThreadFlag)(if (op.n) true else _)

          case StoreGlobal(env) => state.set(_ >> 'globalEnv)(env)
        }
      })

  def execute(op: OpUpcallResume, state: VMState): VMState =
    state.ctxt.ctxt
      .scheduleStrand(state)
      .set(_ >> 'doNextThreadFlag)(true)

  def execute(op: OpNxt, state: VMState): VMState = {
    val (exit, newState) = getNextStrand(state)

    if (exit) {
      newState.set(_ >> 'exitFlag)(true).set(_ >> 'exitCode)(0)
    } else {
      newState
    }
  }

  def execute(op: OpJmp, state: VMState): VMState =
    state.set(_ >> 'pc >> 'relative)(op.n)

  def execute(op: OpJmpCut, state: VMState): VMState = {
    val cut = op.m

    val env = (1 to cut).foldLeft(state.ctxt.env)((env, _) => env.parent)

    state
      .set(_ >> 'ctxt >> 'env)(env)
      .set(_ >> 'pc >> 'relative)(op.n)
  }

  def execute(op: OpJmpFalse, state: VMState): VMState =
    state.update(_ >> 'pc >> 'relative)(
      if (state.ctxt.rslt == Ob.RBLFALSE) op.n else _)

  def execute(op: OpLookupToArg, state: VMState): VMState = {
    val argno = op.a
    val key = state.code.lit(op.v)

    val value =
      state.ctxt.selfEnv.meta.lookupOBO(state.ctxt.selfEnv, key, state.ctxt)

    value match {
      case Left(Upcall) =>
        state.set(_ >> 'doNextThreadFlag)(true)

      case Left(Absent) =>
        handleMissingBinding(key, Location.ArgReg(argno))
        state.set(_ >> 'doNextThreadFlag)(true)

      case Right(ob) =>
        state.update(_ >> 'ctxt >> 'argvec >> 'elem)(_.updated(argno, ob))
    }
  }

  def execute(op: OpLookupToReg, state: VMState): VMState = {
    val regno = op.r
    val key = state.code.lit(op.v)

    val value =
      state.ctxt.selfEnv.meta.lookupOBO(state.ctxt.selfEnv, key, state.ctxt)

    value match {
      case Left(Upcall) =>
        state.set(_ >> 'doNextThreadFlag)(true)

      case Left(Absent) =>
        handleMissingBinding(key, Location.CtxtReg(regno))
        state.set(_ >> 'doNextThreadFlag)(true)

      case Right(ob) => setCtxtReg(regno, ob)(state)
    }
  }

  def execute(op: OpXferLexToArg, state: VMState): VMState = {
    val level = op.l

    val env = (1 to level).foldLeft(state.ctxt.env)((env, _) => env.parent)

    val slot = if (op.i) {
      val actor = new Actor { override val extension: Ob = env }
      actor.extension.slot
    } else {
      env.slot
    }

    state.update(_ >> 'ctxt >> 'argvec >> 'elem)(_.updated(op.a, slot(op.o)))
  }

  def execute(op: OpXferLexToReg, state: VMState): VMState = {
    val level = op.l

    val env = (1 to level).foldLeft(state.ctxt.env)((env, _) => env.parent)

    val slot = if (op.i) {
      val actor = new Actor { override val extension: Ob = env }
      actor.extension.slot
    } else {
      env.slot
    }

    setCtxtReg(op.r, slot(op.o))(state)
  }

  def setCtxtReg(reg: Int, ob: Ob)(state: VMState): VMState =
    state.ctxt.setReg(reg, ob) match {
      case Some(newCtxt) =>
        state.set(_ >> 'ctxt)(newCtxt)
      case None =>
        state
          .set(_ >> 'exitFlag)(true)
          .set(_ >> 'exitCode)(1)
          .update(_ >> 'debugInfo)(info =>
            if (state.debug) info :+ unknownRegister(reg) else info)
    }

  def getCtxtReg(reg: Int)(state: VMState): (Option[Ob], VMState) =
    state.ctxt.getReg(reg) match {
      case someOb => (someOb, state)
      case None =>
        (None,
         state
           .set(_ >> 'exitFlag)(true)
           .set(_ >> 'exitCode)(1)
           .update(_ >> 'debugInfo)(info =>
             if (state.debug) info :+ unknownRegister(reg) else info))
    }

  def execute(op: OpXferGlobalToArg, state: VMState): VMState =
    state.update(_ >> 'ctxt >> 'argvec >> 'elem)(
      _.updated(op.a, state.globalEnv.entry(op.g)))

  val unknownRegister: Int => String = reg => s"Unknown register: $reg"

  def execute(op: OpXferGlobalToReg, state: VMState): VMState =
    setCtxtReg(op.r, state.globalEnv.entry(op.g))(state)

  def execute(op: OpXferArgToArg, state: VMState): VMState =
    state.update(_ >> 'ctxt >> 'argvec >> 'elem)(
      _.updated(op.d, state.ctxt.argvec.elem(op.s)))

  def execute(op: OpXferRsltToArg, state: VMState): VMState =
    state.update(_ >> 'ctxt >> 'argvec >> 'elem)(
      _.updated(op.a, state.ctxt.rslt))

  def execute(op: OpXferArgToRslt, state: VMState): VMState =
    state.set(_ >> 'ctxt >> 'rslt)(state.ctxt.argvec.elem(op.a))

  def execute(op: OpXferRsltToReg, state: VMState): VMState =
    setCtxtReg(op.r, state.ctxt.rslt)(state)

  def execute(op: OpXferRegToRslt, state: VMState): VMState = {
    val (obOpt, newState) = getCtxtReg(op.r)(state)
    obOpt.map(ob => newState.set(_ >> 'ctxt >> 'rslt)(ob)).getOrElse(newState)
  }

  def execute(op: OpXferRsltToDest, state: VMState): VMState =
    state
      .set(_ >> 'loc)(LocationAtom(state.code.lit(op.v)))
      .updateSelf(
        state => {
          import Location._

          Location.store(state.loc,
                         state.ctxt,
                         state.globalEnv,
                         state.ctxt.rslt) match {
            case StoreFail => state.set(_ >> 'vmErrorFlag)(true)

            case StoreCtxt(ctxt) => state.set(_ >> 'ctxt)(ctxt)

            case StoreGlobal(env) => state.set(_ >> 'globalEnv)(env)
          }
        })

  def execute(op: OpXferSrcToRslt, state: VMState): VMState =
    state
      .set(_ >> 'loc)(LocationAtom(state.code.lit(op.v)))
      .set(_ >> 'ctxt >> 'rslt)(
        Location.fetch(state.loc, state.ctxt, state.globalEnv))

  def execute(op: OpIndLitToArg, state: VMState): VMState =
    state.update(_ >> 'ctxt >> 'argvec >> 'elem)(
      _.updated(op.a, state.code.lit(op.v)))

  def execute(op: OpIndLitToReg, state: VMState): VMState =
    setCtxtReg(op.r, state.code.lit(op.v))(state)

  def execute(op: OpIndLitToRslt, state: VMState): VMState =
    state.set(_ >> 'ctxt >> 'rslt)(state.code.lit(op.v))

  def execute(op: OpImmediateLitToArg, state: VMState): VMState =
    state.update(_ >> 'ctxt >> 'argvec >> 'elem)(
      _.updated(op.a, vmLiterals(op.v)))

  def execute(op: OpImmediateLitToReg, state: VMState): VMState =
    setCtxtReg(op.r, vmLiterals(op.v))(state)

  def execute(op: OpUnknown, state: VMState): VMState =
    state.set(_ >> 'exitFlag)(true).set(_ >> 'exitCode)(1)
}
