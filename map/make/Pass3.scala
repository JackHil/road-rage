package map.make

import scala.collection.mutable.HashMap
import scala.collection.mutable.{Set => MutableSet}
import scala.collection.mutable.MultiMap

import map.{Road, Edge, Vertex, Turn, TurnType, Line, Coordinate}

class Pass3(old_graph: PreGraph2) {
  println("Multiplying and directing " + old_graph.edges.length + " edges")
  val graph = new PreGraph3(old_graph)
  val roads_per_vert = new HashMap[Vertex, MutableSet[Road]] with MultiMap[Vertex, Road]

  def run(): PreGraph3 = {
    // TODO two loops (make edge, process it). meh?
    for (r <- graph.roads) {
      roads_per_vert.addBinding(r.v1, r)
      roads_per_vert.addBinding(r.v2, r)

      // pre-compute lines constituting the edges
      for (e <- r.pos_lanes) {
        e.lines = for ((from, to) <- r.points zip r.points.tail)
                  yield shift_line(e.lane_offset, from, to)
      }
      for (e <- r.neg_lanes) {
        e.lines = for ((from, to) <- r.points zip r.points.tail)
                  yield shift_line(e.lane_offset, to, from)
        // TODO inefficient just because i wanted a two-liner?
        e.lines = e.lines.reverse
      }
    }

    println("Connecting the dots...")
    // TODO return the mapping in the future?
    for (v <- graph.vertices) {
      // TODO not sure why this is the case yet
      if (roads_per_vert.contains(v)) {
        connect_vertex(v, roads_per_vert(v))
      } else {
        //println("nothing refs vert " + v)
      }
    }

    // TODO adjust terminal line segments somehow
    // look at the vertex an edge hits, then find the perpendicular(ish) road
    // and trim everything back. but everything? not quite, cause you could have
    // a |--- situation.

    return graph
  }

  // fill out an intersection with turns
  private def connect_vertex(v: Vertex, roads: MutableSet[Road]) = {
    // TODO cfg
    val cross_thresshold = math.Pi / 10 // allow 18 degrees


    // if all edges belong to the same road, this is a dead-end
    if (roads.size == 1) {
      // link corresponding lane numbers
      val r = roads.toList(0)  // TODO ugly way to get the only road?
      for ((from, to) <- r.incoming_lanes(v) zip r.outgoing_lanes(v)) {
        v.turns += new Turn(from, TurnType.UTURN, to)
      }
    }

    // To account for oneways, we actually want to reason about roads that are
    // incoming to or outgoing from this vert.
    val incoming_roads = roads filter (_.incoming_lanes(v).length != 0)
    val outgoing_roads = roads filter (_.outgoing_lanes(v).length != 0)

    // TODO do we need equality on roads? go by id.
    for ((r1, r2) <- incoming_roads zip outgoing_roads if r1 != r2) {
      // analysis is often based on the angle between the parts of the edge's
      // lines that meet
      val angle_btwn = r1.angle_to(r2)

      val from_edges = r1.incoming_lanes(v)  
      val to_edges = r2.outgoing_lanes(v)  

      if (r1.osm_id == r2.osm_id || angle_btwn <= cross_thresshold) {
        // a crossing!
        // essentially zip the from's to the to's, but handle merging:
        // x -> x + n, make the n leftmost lead to the leftmost
        // x + n -> x, make the n rightmost lead to the rightmost

        val lane_diff = to_edges.length - from_edges.length

        // TODO doesnt work :(
        //def cross_turn(from: Edge, to: Edge) = new Turn(from, TurnType.CROSS, to)

        // TODO combo logic somehow? :P
        // TODO also, i'm sure theres off-by-ones here.
        // TODO scalaisms.
        if (lane_diff == 0) {
          for ((from, to) <- from_edges zip to_edges) {
            v.turns += new Turn(from, TurnType.CROSS, to)
          }
          // TODO doesnt work
          //v.turns += (from_edges zip to_edges) map cross_turn
        } else if (lane_diff < 0) {
          // more to less. the leftmost destination gets many sources.
          val (mergers, regulars) = from_edges splitAt (from_edges.length + lane_diff)
          for (from <- mergers) {
            v.turns += new Turn(from, TurnType.CROSS_MERGE, to_edges.last)
          }
          for ((from, to) <- regulars zip to_edges.tail) {
            v.turns += new Turn(from, TurnType.CROSS, to)
          }

          //v.turns += mergers map new Turn(_, TurnType.CROSS_MERGE, to_edges.last)
          //v.turns += (regulars zip to_edges.tail) map turn_factory(TurnType.CROSS)
        } else if (lane_diff > 0) {
          // less to more. the rightmost gets to pick many destinations.
          val (many_sourced, regulars) = to_edges splitAt lane_diff
          for (to <- many_sourced) {
            v.turns += new Turn(from_edges.head, TurnType.CROSS, to)
          }

          //v.turns += many_sourced map new Turn(from_edges.head, TurnType.CROSS, _)
          //v.turns += (from_edges.tail zip regulars) map turn_factory(TurnType.CROSS)
        }
      // TODO the trig is wrong, and the only one lane part is wrong.
      } else if (angle_btwn < math.Pi / 2) {
        // leftmost = highest lane-num
        v.turns += new Turn(from_edges.last, TurnType.LEFT, to_edges.last)
      } else if (angle_btwn >= math.Pi / 2) {
        // rightmost = lowest lane-num
        v.turns += new Turn(from_edges.head, TurnType.RIGHT, to_edges.head)
      }
    }

    // sanity check; make sure every edge is connected
    /*for (incoming <- in_edges if v.turns_to(incoming).length == 0) {
      println("GRR: nowhere to go after " + from)
    }
    for (outgoing <- out_edges if v.turns_from(outgoing).length == 0) {
      println("GRR: nothing leads to " + to)
    }
    */
  }

  private def shift_line(l: Int, pt1: Coordinate, pt2: Coordinate): Line = {
    val width = 0.5   // TODO maplane-width cfg

    // draw lines parallel to center using the perpendicular bisector
    // TODO i dont remember why its counter clockwise here
    val theta_counter = math.atan2(pt2.y - pt1.y, pt2.x - pt1.x) + (math.Pi / 2)
    val dx = l * width * math.cos(theta_counter)
    val dy = l * width * math.sin(theta_counter)

    // + and - for lanes are arbitrary; make things work here
    val line1 = new Line(pt1.x + dx, pt1.y + dy, pt2.x + dx, pt2.y + dy)
    val line2 = new Line(pt1.x - dx, pt1.y - dy, pt2.x - dx, pt2.y - dy)

    // calculate the midpoint of the center road line, turn clockwise 90
    // degrees, and project a point out. We should hit the midpoint of one of
    // these lines -- or hopefully get close.
    val road_line = new Line(pt1.x, pt1.y, pt2.x, pt2.y)
    val mid_road = road_line.midpt
    val theta = theta_counter + math.Pi
    val projected_pt = new Coordinate(
      mid_road.x + (l * width * math.cos(theta)),
      mid_road.y - (l * width * math.sin(theta))    // y inversion
    )

    val error1 = line1.midpt.difference(projected_pt)
    val error2 = line2.midpt.difference(projected_pt)

    // TODO sometimes the mid point is exactly the projected point. when does it
    // actually differ?
    //println(error1 + " vs " + error2);
    
    // TODO why error and not exactly mid1 or mid2 == projected_pt?
    return if (error1 < error2) line1 else line2
  }
}
