// AORTA is copyright (C) 2012 Dustin Carlino, Mike Depinet, and Piyush
// Khandelwal of UT Austin
// License: GNU GPL v2

package utexas.aorta.sim

import utexas.aorta.ui.GUIDebugger
import utexas.aorta.analysis.SimSpeedMonitor

import utexas.aorta.common.{Util, Common, cfg, Flags}

object Headless {
  def main(args: Array[String]): Unit = {
    val sim = Util.process_args(args)
    // TODO move elsewhere?
    Flags.string("--benchmark") match {
      case Some(fn) => new SimSpeedMonitor(sim, fn)
      case None =>
    }
    val gui = new GUIDebugger(sim)

    // Print an update every second
    var last_tick = sim.tick
    sim.listen("headless", _ match {
      case EV_Heartbeat(info) => {
        Util.log("[%.0fx] %s".format(info.tick - last_tick, info.describe))
        last_tick = info.tick
      }
      case _ =>
    })

    Util.log("Starting simulation with time-steps of " + cfg.dt_s + "s")
    val t = Common.timer("headless simulation")
    while (!sim.done) {
      sim.step()
    }
    t.stop
    sys.exit
  }
}
