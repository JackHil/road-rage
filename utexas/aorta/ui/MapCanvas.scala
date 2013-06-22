// AORTA is copyright (C) 2012 Dustin Carlino, Mike Depinet, and Piyush
// Khandelwal of UT Austin
// License: GNU GPL v2

package utexas.aorta.ui

import scala.collection.mutable
import java.awt.{Graphics2D, Shape, BasicStroke, Color, Polygon}
import java.awt.geom._
import swing.event.Key
import swing.Dialog
import scala.language.implicitConversions

import utexas.aorta.map._  // TODO yeah getting lazy.
import utexas.aorta.sim.{Simulation, Agent, Sim_Event, EV_Signal_Change,
                         IntersectionType, RouteType, Route_Event,
                         EV_Transition, EV_Reroute, EV_Heartbeat,
                         EV_Agent_Created, EV_Agent_Destroyed}
import utexas.aorta.sim.PathRoute

import utexas.aorta.{Util, RNG, Common, cfg}

object Mode extends Enumeration {
  type Mode = Value
  val EXPLORE = Value("Explore")  // just chilling
  val PICK_1st = Value("Pick 1st edge") // for now, only client is pathfinding
  val PICK_2nd = Value("Pick 2nd edge")
}

// Cleanly separates GUI state from users of it
class GuiState(val canvas: MapCanvas) {
  // TODO ******** lots of stuff in mapcanvas that just sets/gets us... move it
  // here!

  // Per-render state
  var g2d: Graphics2D = null
  val tooltips = new mutable.ListBuffer[Tooltip]()

  // Permanent state
  var show_tooltips = true
  var current_obj: Option[Renderable] = None
  var camera_agent: Option[Agent] = None
  var route_members = Set[Road]()
  var chosen_road: Option[Road] = None
  var highlight_type: Option[String] = None
  var polygon_roads1: Set[Road] = Set()
  var polygon_roads2: Set[Road] = Set()
  var chosen_edge1: Option[Edge] = None
  var chosen_edge2: Option[Edge] = None

  // Actions
  def reset(g: Graphics2D) {
    g2d = g
    tooltips.clear()
  }

  // Queries
  def current_edge: Option[Edge] = current_obj match {
    case Some(pos: Position) => Some(pos.on.asInstanceOf[Edge])
    case _ => None
  }
  def current_agent: Option[Agent] = current_obj match {
    case Some(a: Agent) => Some(a)
    case _ => None
  }

  // the radius of a small epsilon circle for the cursor
  def eps = 5.0 / canvas.zoom
  def bubble(pt: Coordinate) = new Ellipse2D.Double(
    pt.x - eps, pt.y - eps, eps * 2, eps * 2
  )
}

class MapCanvas(sim: Simulation, headless: Boolean = false) extends ScrollingCanvas {
  ///////////////////////
  // TODO organize better. new magic here.

  private val state = new GuiState(this)

  // TODO this could just be a nice sorted list instead. only have to do lookup
  // when agents are destroyed. could batch those TODO...
  private val driver_renderers = new mutable.HashMap[Agent, DrawDriver]()
  // If we loaded from a savestate, we won't know about these
  for (a <- sim.agents) {
    driver_renderers(a) = new DrawDriver(a, state)
  }

  private val road_renderers = sim.graph.roads.map(r =>
    if (!r.is_oneway)
      new DrawRoad(r, state)
    else
      new DrawOneWayRoad(r, state)
  )

  // TODO eventually, GUI should listen to this and manage the gui, not
  // mapcanvas.
  // Register to hear events
  private var last_tick = 0.0
  sim.listen("ui", (ev: Sim_Event) => { ev match {
    case EV_Heartbeat(info) => {
      update_status()
      status.agents.text = info.describe
      status.time.text = Util.time_num(info.tick)
      last_tick = info.tick
    }
    case EV_Agent_Created(a) => {
      driver_renderers(a) = new DrawDriver(a, state)
    }
    case EV_Agent_Destroyed(a) => {
      driver_renderers -= a
    }
    case EV_Signal_Change(greens) => {
      green_turns.clear
      for (t <- greens) {
        green_turns(t) = GeomFactory.turn_geom(t)
      }
    }
    case _ =>
  } })




  ///////////////////////

  def zoomed_in = zoom > cfg.zoom_threshold

  // A rate of realtime. 1x is realtime.
  var speed_cap: Int = 1

  private var last_render: Long = 0
  private var tick_last_render = 0.0
  private def step_sim() {
    sim.step()
    state.camera_agent match {
      case Some(a) => {
        if (sim.has_agent(a)) {
          center_on(a.at.location)
        } else {
          Util.log(a + " is done; the camera won't stalk them anymore")
          state.camera_agent = None
          state.route_members = Set[Road]()
        }
      }
      case None =>
    }

    // Only render every 0.2 seconds
    val now = System.currentTimeMillis
    // TODO cfg
    if (now - last_render > 200 && sim.tick != tick_last_render ) {
      handle_ev(EV_Action("step"))
      last_render = now
      tick_last_render = sim.tick
    }
  }

  // Headless mode might be controlling us...
  if (!headless) {
    // fire steps every now and then
    new Thread {
      override def run(): Unit = {
        while (true) {
          if (running && speed_cap > 0) {
            val start_time = System.currentTimeMillis
            step_sim()

            // Rate-limit, if need be.
            // In order to make speed_cap ticks per second, each tick needs to
            // last 1000 / speed_cap milliseconds.
            val goal = 
              if (speed_cap > 0)
                (1000 / speed_cap).toInt
              else
                0
            val dt_ms = System.currentTimeMillis - start_time
            if (dt_ms < goal) {
              // Ahead of schedule. Sleep.
              Thread.sleep(goal - dt_ms)
            }
          } else {
            // Just avoid thrashing the CPU.
            Thread.sleep(100)
          }
        }
      }
    }.start
  }

  def update_status() {
    status.sim_speed.text = "%dx / %dx".format((sim.tick - last_tick).toInt, speed_cap)
  }

  // but we can also pause
  var running = false

  // state
  private var current_turn = -1  // for cycling through turns from an edge
  private var mode = Mode.EXPLORE
  private var chosen_pos: Option[Position] = None
  private val green_turns = new mutable.HashMap[Turn, Shape]()
  private var show_green = false
  private val policy_colors = Map(
    IntersectionType.StopSign -> cfg.stopsign_color,
    IntersectionType.Signal -> cfg.signal_color,
    IntersectionType.Reservation -> cfg.reservation_color,
    IntersectionType.CommonCase -> cfg.commoncase_color
  )

  implicit def line2awt(l: Line): Line2D.Double = new Line2D.Double(l.x1, l.y1, l.x2, l.y2)

  def canvas_width = sim.graph.width.toInt
  def canvas_height = sim.graph.height.toInt

  // begin in the center
  x_off = canvas_width / 2
  y_off = canvas_height / 2

  // TODO make this look cooler.
  private val drawing_stroke = new BasicStroke(
    5.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1.0f, Array(1.0f), 0.0f
  )

  // At this point, signal policies have already fired up and sent the first
  // round of greens. We missed it, so compute manually the first time.
  // TODO better solution
  for (v <- sim.vertices) {
    for (t <- v.intersection.policy.current_greens) {
      green_turns(t) = GeomFactory.turn_geom(t)
    }
  }

  def render_canvas(g2d: Graphics2D, window: Rectangle2D.Double): List[Tooltip] = {
    state.reset(g2d)

    val roads_seen = road_renderers.filter(r => {
      val hit = r.hits(window)
      if (hit) {
        r.render_road()
      }
      hit
    })

    // don't show tiny details when it doesn't matter (and when it's expensive
    // to render them all)
    if (zoomed_in) {
      for (r <- roads_seen) {
        r.render_center_line()
        r.render_edges()
      }

      state.current_obj match {
        case Some(pos: Position) => draw_intersection(g2d, pos.on.asInstanceOf[Edge])
        case Some(v: Vertex) => {
          for (t <- v.intersection.policy.current_greens) {
            draw_turn(g2d, t, cfg.turn_color)
          }
        }
        case _ =>
      }

      // Show traffic signal stuff
      if (show_green) {
        g2d.setStroke(GeomFactory.center_stroke)
        g2d.setColor(Color.GREEN)
        /*green_turns.values.foreach(
          t => if (t.intersects(window)) { g2d.draw(t) }
        )*/
        green_turns.foreach(t => if (t._2.intersects(window)) {
          draw_turn(g2d, t._1, Color.GREEN)
        })
      }

      // Illustrate the intersection policies
      for (v <- sim.vertices) {
        val bub = state.bubble(v.location)
        if (bub.intersects(window)) {
          g2d.setColor(policy_colors(v.intersection.policy.policy_type))
          g2d.draw(bub)
        }
      }
    }

    for (driver <- driver_renderers.values) {
      if (driver.hits(window)) {
        driver.render()
      }
    }

    // Finally, if the user is free-handing a region, show their work.
    g2d.setColor(cfg.polygon_color)
    g2d.setStroke(drawing_stroke)
    g2d.draw(polygon)

    // What tooltips do we want?
    state.current_obj match {
      case Some(thing) => {
        state.tooltips += Tooltip(
          screen_to_map_x(mouse_at_x), screen_to_map_y(mouse_at_y),
          thing.tooltip, false
        )
      }
      case None =>
    }
    return state.tooltips.toList
  }

  def draw_turn(g2d: Graphics2D, turn: Turn, color: Color) = {
    val line = GeomFactory.turn_geom(turn)
    g2d.setColor(color)
    g2d.draw(line)
    g2d.fill(GeomFactory.draw_arrow(line, line.shift_back(0.75), 3))
  }

  def draw_intersection(g2d: Graphics2D, e: Edge) = {
    if (current_turn == -1) {
      // show all turns
      for (turn <- e.next_turns) {
        draw_turn(g2d, turn, Color.GREEN)
      }
    } else {
      // show one turn and its conflicts
      val turn = e.next_turns(current_turn)
      draw_turn(g2d, turn, Color.GREEN)

      for (conflict <- turn.conflicts) {
        draw_turn(g2d, conflict, Color.RED)
      }
    }
  }

  def redo_mouseover(x: Double, y: Double): Unit = {
    state.current_obj = None
    current_turn = -1

    if (!zoomed_in) {
      return
    }

    // TODO determine if a low-granularity search to narrow down results helps.

    val cursor = new Rectangle2D.Double(
      x - state.eps, y - state.eps, state.eps * 2, state.eps * 2
    )

    // Order of search: agents, vertices, edges, roads
    // TODO ideally, center agent bubble where the vehicle center is drawn.

    // TODO this is _kind_ of ugly.
    state.current_obj = driver_renderers.values.find(a => a.hits(cursor)) match {
      case None => sim.vertices.find(v => state.bubble(v.location).intersects(cursor)) match {
        case None => road_renderers.flatMap(r => r.edges).find(e => e.hits(cursor)) match {
          case None => road_renderers.find(r => r.hits(cursor)) match {
            case None => None
            case Some(r) => Some(r.road)
          }
          case Some(e) => Some(Position(e.edge, e.edge.approx_dist(new Coordinate(x, y), 1.0)))
        }
        case Some(v) => Some(v)
      }
      case Some(a) => Some(a.agent)
    }
  }

  def handle_ev(ev: UI_Event): Unit = ev match {
    case EV_Action(action) => handle_ev_action(action)
    case EV_Key_Press(key) => handle_ev_keypress(key)
    case EV_Mouse_Moved(x, y) => {
      redo_mouseover(x, y)
      repaint
    }
    case EV_Param_Set("highlight", value) => {
      state.highlight_type = value
      repaint
    }
    case EV_Select_Polygon_For_Army() => {
      // TODO continuation style would make this reasonable:
      // 1) dont keep all that ugly state in the object
      //    (but how to clear it?)
      // 2) code reads like a simple flow

      // Let's find all vertices inside the polygon.
      val rds = sim.vertices.filter(
        v => polygon.contains(v.location.x, v.location.y)).flatMap(v => v.roads
      ).toSet
      Util.log("Matched " + rds.size + " roads")
      if (rds.isEmpty) {
        Util.log("Try that again.")
      } else {
        if (state.polygon_roads1.isEmpty) {
          state.polygon_roads1 = rds
          Util.log("Now select a second set of roads")
        } else {
          state.polygon_roads2 = rds

          prompt_generator(
            state.polygon_roads1.toList.flatMap(_.all_lanes),
            state.polygon_roads2.toList.flatMap(_.all_lanes)
          )

          // Make the keyboard work again
          grab_focus

          state.polygon_roads1 = Set()
          state.polygon_roads2 = Set()
        }
      }
    }
    case EV_Select_Polygon_For_Policy() => {
      // Let's find all vertices inside the polygon.
      val intersections = sim.vertices.filter(
        v => polygon.contains(v.location.x, v.location.y)
      ).map(_.intersection)
      Util.log("Matched " + intersections.size + " intersections")
      Dialog.showInput(
        message = "What policy should govern these intersections?",
        initial = "",
        entries = IntersectionType.values.toList
      ) match {
        case Some(name) => {
          // TODO make a new scenario...
          /*val builder = Simulation.policy_builder(IntersectionType.withName(name.toString))
          intersections.foreach(i => i.policy = builder(i))*/
        }
        case None =>
      }
    }
    case EV_Select_Polygon_For_Serialization() => {
      val dir = s"maps/area_${sim.graph.name}"
      Util.mkdir(dir)
      Dialog.showInput(message = "Name this area", initial = "") match {
        case Some(name) => {
          val edges = sim.vertices.filter(
            v => polygon.contains(v.location.x, v.location.y)).flatMap(v => v.edges
          ).map(_.id).toArray
          val w = Util.writer(s"${dir}/${name}")
          w.int(edges.size)
          edges.foreach(id => w.int(id))
          w.done()
          Util.log(s"Area saved to ${dir}/${name}")
        }
        case None =>
      }
    }
    case _ =>
  }

  def handle_ev_action(ev: String): Unit = ev match {
    case "spawn-army" => {
      prompt_generator(sim.edges, sim.edges)
    }
    case "step" => {
      val note = if (running)
                   ""
                 else
                   " [Paused]"
      repaint
    }
    case "toggle-running" => {
      if (running) {
        running = false
        status.sim_speed.text = s"Paused / $speed_cap"
      } else {
        running = true
      }
    }
    case "pathfind" => {
      switch_mode(Mode.PICK_1st)
      state.chosen_edge1 = None
      state.chosen_edge2 = None
      state.route_members = Set[Road]()
      repaint
    }
    case "clear-route" => {
      switch_mode(Mode.EXPLORE)
      state.chosen_edge1 = None
      state.chosen_edge2 = None
      state.route_members = Set[Road]()
      repaint
    }
    // TODO refactor the 4 teleports?
    case "teleport-edge" => {
      prompt_int("What edge ID do you seek?") match {
        case Some(id) => {
          try {
            val e = sim.edges(id.toInt)
            // TODO center on some part of the edge and zoom in, rather than
            // just vaguely moving that way
            Util.log("Here's " + e)
            center_on(e.lines.head.start)
            state.chosen_edge2 = Some(e)  // just kind of use this to highlight it
            repaint
          } catch {
            case _: NumberFormatException => Util.log("Bad edge ID " + id)
          }
        }
        case _ =>
      }
      grab_focus
    }
    case "teleport-road" => {
      prompt_int("What road ID do you seek?") match {
        case Some(id) => {
          try {
            val r = sim.roads(id.toInt)
            // TODO center on some part of the road and zoom in, rather than
            // just vaguely moving that way
            Util.log("Here's " + r)
            center_on(r.all_lanes.head.lines.head.start)
            state.chosen_road = Some(r)
            repaint
          } catch {
            case _: NumberFormatException => Util.log("Bad edge ID " + id)
          }
        }
        case _ =>
      }
      grab_focus
    }
    case "teleport-agent" => {
      prompt_int("What agent ID do you seek?") match {
        case Some(id) => {
          try {
            sim.get_agent(id.toInt) match {
              case Some(a) => {
                Util.log("Here's " + a)
                state.current_obj = Some(a)
                handle_ev_keypress(Key.F)
                repaint
              }
              case _ => Util.log("Didn't find " + id)
            }
          } catch {
            case _: NumberFormatException => Util.log("Bad agent ID " + id)
          }
        }
        case _ =>
      }
      grab_focus
    }
    case "teleport-vertex" => {
      prompt_int("What vertex ID do you seek?") match {
        case Some(id) => {
          try {
            val v = sim.vertices(id.toInt)
            Util.log("Here's " + v)
            center_on(v.location)
            repaint
          } catch {
            case _: NumberFormatException => Util.log("Bad vertex ID " + id)
          }
        }
        case _ =>
      }
      grab_focus
    }
  }

  def handle_ev_keypress(key: Any): Unit = key match {
    // TODO this'll be tab someday, i vow!
    case Key.Control => {
      // cycle through turns
      state.current_edge match {
        case Some(e) => {
          current_turn += 1
          if (current_turn >= e.next_turns.size) {
            current_turn = 0
          }
          repaint
        }
        case None =>
      }
    }
    case Key.P => {
      handle_ev(EV_Action("toggle-running"))
    }
    case Key.C if state.current_edge.isDefined => {
      mode match {
        case Mode.PICK_1st => {
          state.chosen_edge1 = state.current_edge
          switch_mode(Mode.PICK_2nd)
          repaint
        }
        case Mode.PICK_2nd => {
          state.chosen_edge2 = state.current_edge
          // TODO later, let this inform any client
          show_pathfinding
          switch_mode(Mode.EXPLORE)
        }
        case _ =>
      }
    }
    case Key.M if state.current_edge.isDefined => {
      mode match {
        case Mode.EXPLORE => {
          state.chosen_edge1 = state.current_edge
          chosen_pos = state.current_obj.asInstanceOf[Option[Position]]
          switch_mode(Mode.PICK_2nd)
          repaint
        }
        case Mode.PICK_2nd => {
          state.chosen_edge2 = state.current_edge
          // TODO make one agent from chosen_pos to current_edge
          state.chosen_edge1 = None
          state.chosen_edge2 = None
          chosen_pos = None
          switch_mode(Mode.EXPLORE)
          repaint
        }
        case _ =>
      }
    }
    case Key.OpenBracket => {
      speed_cap = math.max(0, speed_cap - 1)
      update_status()
    }
    case Key.CloseBracket => {
      speed_cap += 1
      update_status()
    }
    case Key.D => state.current_obj match {
      case Some(thing) => thing.debug
      case None =>
    }
    case Key.F => {
      // Unregister old listener
      state.camera_agent match {
        case Some(a) => a.route.unlisten("UI")
        case None =>
      }

      state.camera_agent = state.current_agent
      state.camera_agent match {
        case Some(a) => a.route match {
          case r: PathRoute => {
            state.route_members = r.roads
            r.listen("UI", (ev: Route_Event) => { ev match {
              case EV_Reroute(path) => {
                state.route_members = path.map(_.road).toSet
              }
              case EV_Transition(from, to) => from match {
                case e: Edge => {
                  state.route_members -= e.road
                }
                case _ =>
              }
            } })
          }
          case _ =>
        }
        case None => {
          state.route_members = Set[Road]()
        }
      }
    }
    case Key.X if state.current_agent.isDefined => {
      if (running) {
        Util.log("Cannot nuke agents while simulation is running!")
      } else {
        val a = state.current_agent.get
        state.current_obj = None
        Util.log("WARNING: Nuking " + a)
        //a.terminate
        // TODO remove the agent from the list
      }
    }
    case Key.G => {
      show_green = !show_green
    }
    case Key.T => {
      state.show_tooltips = !state.show_tooltips
    }
    case Key.Q => {
      // Run some sort of debuggy thing
      sim.ui_debug
    }
    case _ => // Ignore the rest
  }

  def prompt_generator(src: Seq[Edge], dst: Seq[Edge]): Unit = {
    Util.log("Creating a new generator...")

    // Ask: what type of behavior and route strategy
    // TODO plumb behavior through, too, once there's reason to
    val route_type = Dialog.showInput(
      message = "How should the agents route to their destination?",
      initial = "",
      entries = RouteType.values.toList
    ) match {
      case Some(name) => RouteType.withName(name.toString)
      case None => return
    }

    // Ask: fixed (how many) or continuous (how many per what time)
    // TODO improve the UI.
    Dialog.showOptions(
      message = "Want a fixed, one-time burst or a continuous generator?",
      optionType = Dialog.Options.YesNoCancel, initial = 0,
      entries = Seq("Constant", "Continuous")
    ) match {
      case Dialog.Result.Yes => {
        // Fixed
        prompt_int("How many agents?") match {
          case Some(num) => {
            // TODO make em
          }
          case _ =>
        }
      }
      case Dialog.Result.No => {
        // Continuous
        prompt_double(
          "How often (in simulation-time seconds) do you want one new agent?"
        ) match {
          case Some(time) => {
            // TODO make em
          }
          case _ =>
        }
      }
    }
  }

  def switch_mode(m: Mode.Mode) = {
    mode = m
  }

  def show_pathfinding() = {
    // contract: must be called when current_edge1 and 2 are set
    val from = state.chosen_edge1.get.directed_road
    val to = state.chosen_edge2.get.directed_road

    val timer = Common.timer("Pathfinding")
    // Can choose a specific router...
    val route = sim.graph.congestion_router.path(from, to, sim.tick)
    route.foreach(step => println("  - " + step))
    timer.stop()

    // Filter and just remember the edges; the UI doesn't want to highlight
    // turns.
    // TODO pathfinding is by directed road now, not edge. just pick some edge
    // in each group.
    state.route_members = route.map(_.road).toSet
    repaint
  }
}

// TODO what else belongs?
object GeomFactory {
  private val rng = new RNG()

  // pre-compute; we don't have more than max_lanes
  private val lane_line_width = 0.6f  // TODO cfg
  val strokes = (0 until cfg.max_lanes).map(
    n => new BasicStroke(lane_line_width * n.toFloat)
  )

  // TODO cfg
  /*private val center_stroke = new BasicStroke(
    0.1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1.0f, Array(1.0f), 0.0f
  )*/
  val center_stroke     = new BasicStroke(0.1f)
  val lane_stroke       = new BasicStroke(0.05f)

  def draw_arrow(line: Line, base: Coordinate, size: Int): Shape = {
    // TODO enum for size
    // width = how far out is the tip
    val width = size match {
      case 3 => 0.25
      case 2 => 0.15
      case _ => 0.1
    }
    // height = how tall is the arrow
    val height = size match {
      case 3 => 1.0
      case 2 => 0.75
      case _ => 0.5
    }

    val theta = line.broken_angle
    val x = base.x + (height * math.cos(theta))
    val y = base.y + (height * math.sin(theta))

    // Perpendiculous!
    val theta_perp1 = theta + (math.Pi / 2)
    val cos_perp1 = width * math.cos(theta_perp1)
    val sin_perp1 = width * math.sin(theta_perp1)

    val theta_perp2 = theta - (math.Pi / 2)
    val cos_perp2 = width * math.cos(theta_perp2)
    val sin_perp2 = width * math.sin(theta_perp2)

    val arrow = new Path2D.Double()
    arrow.moveTo(x, y)
    arrow.lineTo(base.x + cos_perp1, base.y + sin_perp1)
    arrow.lineTo(base.x + cos_perp2, base.y + sin_perp2)
    return arrow
  }

  def turn_geom(turn: Turn): Line = {
    // We don't use the conflict_line, since that doesn't draw very
    // informatively, unless lane lines are trimmed back well.
    // Shift the lines to match the EdgeLines we draw.
    val pt1 = turn.from.lines.last.perp_shift(0.5).shift_back()
    val pt2 = turn.to.lines.head.perp_shift(0.5).shift_fwd()
    return new Line(pt1, pt2)
  }

  def rand_color() = new Color(
    rng.double(0.0, 1.0).toFloat,
    rng.double(0.0, 1.0).toFloat,
    rng.double(0.0, 1.0).toFloat
  )
}
