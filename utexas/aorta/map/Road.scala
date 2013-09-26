// AORTA is copyright (C) 2012 Dustin Carlino, Mike Depinet, and Piyush
// Khandelwal of UT Austin
// License: GNU GPL v2

package utexas.aorta.map

import scala.collection.mutable.MutableList
import Function.tupled

import utexas.aorta.ui.Renderable

import utexas.aorta.common.{Util, StateWriter, StateReader, Physics, RoadID,
                            VertexID}

// TODO enum for type. also, it's var because of tarjan's...
// TODO var id due to tarjan
// TODO speed limit stored directly.
class Road(
  var id: RoadID, val length: Double, val name: String, var road_type: String,
  val osm_id: String, v1_id: VertexID, v2_id: VertexID, var points: Array[Coordinate]
) extends Renderable
{
  //////////////////////////////////////////////////////////////////////////////
  // Deterministic state

  var v1: Vertex = null
  var v2: Vertex = null

  // TODO move the lanes to be part of the D.R.
  var pos_group: Option[DirectedRoad] = Some(new DirectedRoad(
    this, Road.next_directed_id, Direction.POS
  ))
  var neg_group: Option[DirectedRoad] = Some(new DirectedRoad(
    this, Road.next_directed_id, Direction.NEG
  ))

  // + lanes go from v1->v2; - lanes go from v2->v1
  val pos_lanes = new MutableList[Edge]
  val neg_lanes = new MutableList[Edge]

  // TODO move this table
  val speed_limit = Physics.mph_to_si(road_type match {
    case "residential"    => 30
    case "motorway"       => 80
    // Actually these don't have a speed limit legally...  35 is suggested, but NOBODY does that
    case "motorway_link"  => 70
    case "trunk"          => 70
    case "trunk_link"     => 60
    case "primary"        => 65
    case "primary_link"   => 55
    case "secondary"      => 55
    case "secondary_link" => 45
    case "tertiary"       => 45
    case "tertiary_link"  => 35
    //case "unclassified"   => 40
    //case "road"           => 40
    case "living_street"  => 20
    // TODO some of these we filter out in Pass 1... cross-ref with that list
    case "service"        => 10 // This is apparently parking-lots basically, not feeder roads
    case "services"       => 10
    //case "track"          => 35
    // I feel the need.  The need for speed.  Where can we find one of these?
    case "raceway"        => 300
    //case "null"           => 30
    //case "proposed"       => 35
    //case "construction"     => 20
    
    case _                => 35 // Generally a safe speed, right?
  })

  //////////////////////////////////////////////////////////////////////////////
  // Meta

  def serialize(w: StateWriter) {
    w.int(id.int)
    w.double(length)
    w.string(name)
    w.string(road_type)
    w.string(osm_id)
    w.int(v1.id.int)
    w.int(v2.id.int)
    w.int(points.size)
    for (pt <- points) {
      w.double(pt.x)
      w.double(pt.y)
    }
  }

  def setup(g: GraphLike) {
    v1 = g.get_v(v1_id)
    v2 = g.get_v(v2_id)

    if (pos_lanes.isEmpty) {
      pos_group = None
    }
    if (neg_lanes.isEmpty) {
      neg_group = None
    }

    // check invariants of points -- oops, not true anymore since we merge short
    // roads
    //Util.assert_eq(v1.location, points.head)
    //Util.assert_eq(v2.location, points.last)
  }

  //////////////////////////////////////////////////////////////////////////////
  // Queries

  def all_lanes() = pos_lanes ++ neg_lanes
  def other_vert(v: Vertex) = if (v == v1) v2 else v1

  def is_oneway = pos_lanes.length == 0 || neg_lanes.length == 0
  // TODO assert is_oneway, maybe. or maybe even case class...
  def oneway_lanes = if (pos_lanes.length == 0) neg_lanes else pos_lanes

  def num_lanes = pos_lanes.length + neg_lanes.length

  override def toString = name + " [R" + id + "]"
  
  // TODO don't ask for vertex that isn't either v1 or v2.
  def incoming_lanes(v: Vertex) = if (v == v1) neg_lanes else pos_lanes
  def outgoing_lanes(v: Vertex) = if (v == v1) pos_lanes else neg_lanes

  def pairs_of_points = points.zip(points.tail)

  def debug() {
    Util.log(this + " is a " + road_type + " of length " + length + " meters")
  }

  // For debug only
  def doomed = all_lanes.find(e => e.doomed).isDefined

  // TODO better heuristic, based on how much this extended road touches other
  // roads
  def is_major = road_type != "residential"

  def congestion_rating = all_lanes.map(_.congestion_rating).max
}

object Road {
  def unserialize(r: StateReader) = new Road(
    new RoadID(r.int), r.double, r.string, r.string, r.string,
    new VertexID(r.int), new VertexID(r.int),
    Range(0, r.int).map(_ => new Coordinate(r.double, r.double)).toArray
  )

  def road_len(pts: Iterable[Coordinate]) =
    pts.zip(pts.tail).map(tupled((p1, p2) => new Line(p1, p2).length)).sum

  // PreGraph3's fix_ids also mods us.
  var num_directed_roads = 0
  def next_directed_id(): Int = {
    val id = num_directed_roads
    num_directed_roads += 1
    return id
  }
}
