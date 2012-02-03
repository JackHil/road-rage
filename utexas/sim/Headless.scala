package utexas.sim

import utexas.{Util, cfg}

object Headless {
  def process_args(args: Array[String]): Simulation = {
    val keys = args.zipWithIndex.filter(p => p._2 % 2 == 0).map(p => p._1)
    val vals = args.zipWithIndex.filter(p => p._2 % 2 == 1).map(p => p._1)
    var fn = "dat/test.map"
    var rng = System.currentTimeMillis

    if (args.size % 2 != 0) {
      Util.log("Command-line parameters must be pairs of key => value")
    }                                                                     

    for ((key, value) <- keys.zip(vals)) {
      key match {
        case "--input" => { fn = value }
        case "--rng"   => { rng = value.toLong }
        case _         => { Util.log("Unknown argument: " + value); sys.exit }
      }
    }
    Util.init_rng(rng)
    return Simulation.load(fn)
  }

  def main(args: Array[String]) = {
    val sim = process_args(args)
    sim.spawn_army(1, 100)

    val timer = Util.timer("running the sim")
    Util.log("Starting simulation with time-steps of " + cfg.dt_s + "s")
    var last_time = 0.0
    while (!sim.agents.isEmpty) {
      if (sim.tick - last_time >= 1.0) {
        Util.log(sim.agents.size + " agents left at t=" + sim.tick)
        last_time = sim.tick
      }
      sim.step(cfg.dt_s)
    }
    Util.log("Simulation took " + sim.tick + " virtual seconds")
    timer.stop
    Util.log("Average of " + (sim.tick / timer.so_far) + "x speedup with dt=" + cfg.dt_s)
  }
}
