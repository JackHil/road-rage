// AORTA is copyright (C) 2012 Dustin Carlino, Mike Depinet, and Piyush
// Khandelwal of UT Austin
// License: GNU GPL v2

package utexas.aorta.contrib

import utexas.aorta.sim.RoadAgent
import utexas.aorta.sim.drivers.Agent

// Manage reservations to use roads
class Tollbooth(road: RoadAgent) {
  // TODO serialization and such

  def when_enter(a: Agent) {}

  def when_exit(a: Agent) {}
}
