// AORTA is copyright (C) 2012 Dustin Carlino, Mike Depinet, and Piyush
// Khandelwal of UT Austin
// License: GNU GPL v2

package utexas.aorta

import scala.util.Random
import java.io.FileWriter
import scala.annotation.elidable
import scala.annotation.elidable.ASSERTION

import utexas.aorta.sim.Simulation
import utexas.aorta.analysis.{Stats, Profiling}

object Util {
  private var indent_log = 0
  def log_push = { indent_log += 1 }
  def log_pop =  { indent_log -= 1 }
  def indent = "  " * indent_log
  def log(msg: String) = println(indent + msg)

  // For simulation stuff
  // TODO if diff threads ever make use of this, lock or duplicate
  private var sim_rng: Random = null  // icky...
  // For the UI and stuff that doesn't matter. Separate these so determinism
  // works between headless/UI.
  val util_rng = new Random()
  var seed: Long = -1

  def init_rng(s: Long) = {
    seed = s
    sim_rng = new Random(seed)
    Util.log("RNG seed: " + seed)
  }

  private var dump_at_shutdown = false

  // Convenient to see this at the very end if it was a long log.
  scala.sys.ShutdownHookThread({
    if (dump_at_shutdown) {
      println("")
      println("-" * 80)
      if (seed != -1) {
        Util.log("\nRNG seed: " + Util.seed)
      }
      println("")
      Stats.shutdown
      println("")
      Profiling.shutdown
    }
  })

  def rand_double(min: Double, max: Double, rng: Random = sim_rng): Double =
    if (min > max)
      throw new Exception("rand(" + min + ", " + max + ") requested")
    else if (min == max)
      min
    else
      min + rng.nextDouble * (max - min)
  def rand_int(min: Int, max: Int) = rand_double(min, max).toInt
  def choose_rand[T](from: Seq[T]): T = from(sim_rng.nextInt(from.length))
  // return true 'p'% of the time. p is [0.0, 1.0]
  def percent(p: Double) = rand_double(0.0, 1.0) < p

  // due to internationalization, printf doesn't have a way of adding commas
  // after every 3 orders of mag
  def comma_num(n: Int, pad: Boolean = true): String =
    if (n < 1000 && pad)
      "%03d".format(n)
    else if (n < 1000)
      "" + n
    else
      comma_num(n / 1000, pad = false) + "," + comma_num(n % 1000)

  @elidable(ASSERTION) def assert_eq(a: Any, b: Any) = assert(a == b, a + " != " + b)
  @elidable(ASSERTION) def assert_ne(a: Any, b: Any) = assert(a != b, a + " == " + b)
  @elidable(ASSERTION) def assert_gt(a: Double, b: Double) = assert(a > b, a + " <= " + b)
  @elidable(ASSERTION) def assert_ge(a: Double, b: Double) = assert(a >= b, a + " < " + b)
  @elidable(ASSERTION) def assert_lt(a: Double, b: Double) = assert(a < b, a + " >= " + b)
  @elidable(ASSERTION) def assert_le(a: Double, b: Double) = assert(a <= b, a + " > " + b)

  // to meters/sec, that is. SI units.
  def mph_to_si(r: Double) = r * 0.44704

  // Capped when speed goes negative.
  def dist_at_constant_accel(accel: Double, time: Double, initial_speed: Double): Double = {
    // Don't deaccelerate into going backwards, just cap things off.
    val actual_time = if (accel >= 0)
                        time
                      else
                        math.min(time, -1 * initial_speed / accel)
    return (initial_speed * actual_time) + (0.5 * accel * (actual_time * actual_time))
  }
  def accel_to_achieve(cur_speed: Double, target_speed: Double)
    = (target_speed - cur_speed) / cfg.dt_s
  // find the time to cover dist by accelerating first, then cruising at
  // constant speed
  def two_phase_time(speed_i: Double = 0, speed_f: Double = 0, dist: Double = 0,
                     accel: Double = 0): Double =
  {
    // v_f = v_i + a*t
    val time_to_cruise = (speed_f - speed_i) / accel
    val dist_during_accel = dist_at_constant_accel(
      accel, time_to_cruise, speed_i
    )

    return if (dist_during_accel < dist) {
      // so then the remainder happens at constant cruisin speed
      return time_to_cruise + ((dist - dist_during_accel) / speed_f)
    } else {
      // We spent the whole time accelerating
      // solve dist = a(t^2) + (v_i)t
      val discrim = math.sqrt((speed_i * speed_i) + (4 * accel * dist))
      val time = (-speed_i + discrim) / (2 * accel) // this is the positive root
      assert_ge(time, 0)   // make sure we have the right solution to this
      time
    }
  }

  // The boolean says whether or not a pre-defined scenario is being run.
  def process_args(args: Array[String], with_geo: Boolean, shutdown: Boolean): (Simulation, Boolean) = {
    dump_at_shutdown = shutdown
    // TODO write with 'partition'
    val keys = args.zipWithIndex.filter(p => p._2 % 2 == 0).map(p => p._1)
    val vals = args.zipWithIndex.filter(p => p._2 % 2 == 1).map(p => p._1)
    var fn = ""
    var rng = System.currentTimeMillis
    var diff_rng = false
    var load_scenario = ""
    var exp_name = ""
    var with_geometry = with_geo  // allow override with parameters

    if (args.size % 2 != 0) {
      // TODO better usage
      Util.log("Command-line parameters must be pairs of key => value")
      sys.exit
    }                                                                     

    val cfg_prefix = """--cfg_(\w+)""".r
    for ((key, value) <- keys.zip(vals)) {
      // TODO multiple different ways of saying 'true'
      key match {
        case "--input"       => { fn = value }
        case "--rng"         => { rng = value.toLong; diff_rng = true }
        case "--print_stats" => { Stats.use_print = value == "1" }
        case "--log_stats"   => { Stats.use_log = value == "1" }
        case "--use_geo"     => { with_geometry = value == "1" }
        // TODO case "--run_for"     => { run_for = value.toDouble }
        case "--scenario"    => { load_scenario = value }
        case "--name"        => { exp_name = value }
        case cfg_prefix(param)   => cfg.get_param_method(param) match {
          case Some(method) => {
            val param_type = method.getParameterTypes.head
            if (param_type == classOf[Int]) {
              method.invoke(cfg, value.toInt: java.lang.Integer)
            } else if (param_type == classOf[Double]) {
              method.invoke(cfg, value.toDouble: java.lang.Double)
            } else if (param_type == classOf[Boolean]) {
              method.invoke(cfg, (value == "1"): java.lang.Boolean)
            } else {
              method.invoke(cfg, value: java.lang.String)
            }
          }
          case None => { Util.log(s"No parameter $param"); sys.exit }
        }
        case _               => { Util.log("Unknown argument: " + key); sys.exit }
      }
    }
    if (exp_name.isEmpty) {
      exp_name = "Experiment " + rng
    }
    Stats.setup_experiment(exp_name)

    return if (load_scenario.isEmpty) {
      Util.init_rng(rng)
      (Simulation.load(fn, with_geometry), false)
    } else {
      val sim = Simulation.load_scenario(load_scenario, with_geometry)
      // It's useful to retry a scenario with a new seed.
      if (diff_rng) {
        Util.init_rng(rng)
      }
      (sim, true)
    }
  }
}

object cfg {
  // TODO marking things as configurable or not from the UI.
  val bools = List(
    ("antialias",       true,  "Draw nicer lines more slowly?"),
    ("draw_cursor",     false, "Draw the epsilon bubble around the cursor?"),
    ("draw_lane_arrow", true,  "Draw arrows to show lane orientation?"),
    ("dash_center",     true,  "Dash the center yellow line?")
  ).map({c => c._1 -> new Bool_Cfgable(c._2, c._3)}).toMap

  // all distances should be in meters
  val doubles = List(
    ("lane_width",      0.5, "Width of a lane in meters", 0.01, 0.1),
    ("zoom_threshold",  5.0, "How close to zoom in before drawing details",    1.0, 15.0),
    ("epsilon",       1E-10, "What do we take as zero due to FP imprecision?", 0.0,  1.0),
    ("dt_s",            0.1, "The only dt in seconds an agent can experience", 0.1,  3.0),
    // account for crosswalks, vehicle length...
    ("end_threshold",   5.0, "The end of a traversable is its length - this",  0.1,  3.0),
    // this kind of gives dimension to cars, actually
    ("follow_dist",     20.0, "Even if stopped, don't get closer than this", 0.1, 1.0),
    ("max_accel",       2.7, "A car's physical capability",                  0.5, 5.0),
    ("pause_at_stop",   1.0, "Wait this long at a stop sign before going",   0.5, 5.0),
    ("min_lane_length", 0.1, "It's unreasonable to have anything shorter",   0.0, 1.0),
    ("thruput_stat_time", 60.0, "Record throughput per this time",   0.5, 5.0)
  ).map({c => c._1 -> new Double_Cfgable(c._2, c._3, c._4, c._5)}).toMap

  val ints = List(
    ("max_lanes",  50,  "The max total lanes a road could have", 1, 30),
    ("army_size",  5000, "How many agents to spawn by default", 1, 10000),
    ("signal_duration",  60, "How long for a traffic signal to go through all of its cycles", 4, 1200)
  ).map({c => c._1 -> new Int_Cfgable(c._2, c._3, c._4, c._5)}).toMap

  val strings = List(
    ("policy", "StopSign"),
    ("ordering", "FIFO")
  ).map({c => c._1 -> new String_Cfgable(c._2)}).toMap

  // TODO val colors = ... (I'm not kidding)
  // TODO and of course, a way to save the settings.

  // TODO i wanted to use reflection to generate methods, but that won't work...
  // jvm limitations.
  // TODO these shortcuts take a bit extra memory, but avoid lots of hash
  // lookups. real solution is to not have methods and then also objects...

  var antialias = false
  var draw_cursor = false
  var draw_lane_arrow = false
  var dash_center = false

  var lane_width = 0.0
  var zoom_threshold = 0.0
  var epsilon = 0.0
  var end_threshold = 0.0
  var follow_dist = 0.0
  var max_accel = 0.0
  var pause_at_stop = 0.0
  var min_lane_length = 0.0
  var thruput_stat_time = 0.0
  var dt_s = 0.0

  var max_lanes = 0
  var army_size = 0
  var signal_duration = 0

  var policy = ""
  var ordering = ""

  // Do this early.
  init_params

  def lanechange_dist = lane_width * 5.0

  def get_param_method(name: String) =
    cfg.getClass.getDeclaredMethods.find(_.getName == name + "_$eq")

  // Set all the config stuff from the hashes.
  def init_params() = {
    for ((key, value) <- bools) {
      get_param_method(key).get.invoke(cfg, value.value: java.lang.Boolean)
    }
    for ((key, value) <- doubles) {
      get_param_method(key).get.invoke(cfg, value.value: java.lang.Double)
    }
    for ((key, value) <- ints) {
      get_param_method(key).get.invoke(cfg, value.value: java.lang.Integer)
    }
    for ((key, value) <- strings) {
      get_param_method(key).get.invoke(cfg, value.value: java.lang.String)
    }
  }
}

// couldn't quite the OO work out to bundle these
class Bool_Cfgable(default: Boolean, descr: String) {
  var value = default
}

class Double_Cfgable(default: Double, descr: String, min: Double, max: Double) {
  var value = default
}

class Int_Cfgable(default: Int, descr: String, min: Int, max: Int) {
  var value = default
}

class String_Cfgable(default: String) {
  var value = default
}
