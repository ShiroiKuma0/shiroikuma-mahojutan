// Fork: the rules for running several paired transfers at once.
//
// A paired transfer over the shared network is one TCP connection with its own Noise
// session, so nothing on the wire ever stopped two of them coexisting — what stopped them
// was the single transfer slot the app was built around. A **lane** is one such transfer,
// with its own socket, file list, conflict rule, progress and cancel handle. This file is
// only the bookkeeping: who may start, who waits, who is refused. It holds no sockets and
// no tasks, which is what lets every rule below be tested without a network.
//
// The rules, which the Android app follows identically:
//
//   * at most MAX_LANES lanes run at once;
//   * a send to a peer that already has a lane with this device — in either direction,
//     running or waiting — is queued behind it, as is a send that finds every lane taken;
//   * an incoming transfer is never queued: it is accepted while a lane is free, and
//     refused as busy otherwise. One arriving from a peer that already has a lane is
//     accepted — only the sending side queues;
//   * hotspot and password-mode transfers keep the single primary slot and are exclusive
//     with lanes in both directions: the radio or port they need cannot be shared.

/// How many paired transfers may run at once (白い熊, 2026-11-17). Enough that a small send
/// never waits behind a long one, few enough that many small ones cannot starve it.
pub const MAX_LANES: usize = 4;

pub type LaneId = u64;

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "lowercase")]
pub enum LaneDirection {
    Send,
    Receive,
}

#[derive(Clone, Debug, serde::Serialize)]
pub struct Lane {
    pub id: LaneId,
    /// The peer's device id in its text form, which is what "the same peer" is decided by.
    pub peer_id: String,
    pub peer_name: String,
    pub direction: LaneDirection,
    /// Waiting for its turn rather than running. Only ever true for a send.
    pub queued: bool,
}

/// Why a lane could not be opened at all.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LaneRefusal {
    /// A hotspot or password-mode transfer holds the primary slot.
    PrimaryBusy,
    /// Every lane is running. Only an incoming transfer is refused for this; a send queues.
    Full,
}

/// What became of a request to send.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SendAdmission {
    Run(LaneId),
    Queue(LaneId),
}

#[derive(Default)]
pub struct LaneBoard {
    next_id: LaneId,
    // In the order they were opened, which is the order queued sends are started in.
    lanes: Vec<Lane>,
}

impl LaneBoard {
    pub fn new() -> Self {
        Self::default()
    }

    fn open(&mut self, peer_id: &str, peer_name: &str, direction: LaneDirection, queued: bool) -> LaneId {
        self.next_id += 1;
        self.lanes.push(Lane {
            id: self.next_id,
            peer_id: peer_id.to_string(),
            peer_name: peer_name.to_string(),
            direction,
            queued,
        });
        self.next_id
    }

    /// Lanes actually moving data.
    pub fn running(&self) -> usize {
        self.lanes.iter().filter(|l| !l.queued).count()
    }

    /// Anything at all, running or waiting. The primary slot may not start while this holds:
    /// a queued send is a promise, and raising a hotspot under it would break the promise.
    pub fn is_active(&self) -> bool {
        !self.lanes.is_empty()
    }

    pub fn get(&self, id: LaneId) -> Option<&Lane> {
        self.lanes.iter().find(|l| l.id == id)
    }

    pub fn list(&self) -> Vec<Lane> {
        self.lanes.clone()
    }

    /// A send to `peer_id`. Runs at once when a lane is free and the peer has none; waits
    /// otherwise. A peer's *queued* lane counts as its having one, so sends to one device
    /// start in the order they were asked for.
    pub fn request_send(
        &mut self,
        peer_id: &str,
        peer_name: &str,
        primary_busy: bool,
    ) -> Result<SendAdmission, LaneRefusal> {
        if primary_busy {
            return Err(LaneRefusal::PrimaryBusy);
        }
        let peer_has_lane = self.lanes.iter().any(|l| l.peer_id == peer_id);
        if peer_has_lane || self.running() >= MAX_LANES {
            Ok(SendAdmission::Queue(self.open(
                peer_id,
                peer_name,
                LaneDirection::Send,
                true,
            )))
        } else {
            Ok(SendAdmission::Run(self.open(
                peer_id,
                peer_name,
                LaneDirection::Send,
                false,
            )))
        }
    }

    /// A transfer arriving from `peer_id`. Never queued — the far device's user is the one
    /// watching, and "busy" is something they can act on.
    pub fn admit_receive(
        &mut self,
        peer_id: &str,
        peer_name: &str,
        primary_busy: bool,
    ) -> Result<LaneId, LaneRefusal> {
        if primary_busy {
            return Err(LaneRefusal::PrimaryBusy);
        }
        if self.running() >= MAX_LANES {
            return Err(LaneRefusal::Full);
        }
        Ok(self.open(peer_id, peer_name, LaneDirection::Receive, false))
    }

    /// Removes a lane — finished, failed, or cancelled, running or queued — and returns the
    /// queued sends that may now start, already marked running, oldest first.
    pub fn finish(&mut self, id: LaneId) -> Vec<LaneId> {
        self.lanes.retain(|l| l.id != id);
        let mut started = Vec::new();
        for i in 0..self.lanes.len() {
            if !self.lanes[i].queued {
                continue;
            }
            if self.running() >= MAX_LANES {
                break;
            }
            let peer_running = self
                .lanes
                .iter()
                .any(|l| !l.queued && l.peer_id == self.lanes[i].peer_id);
            if !peer_running {
                self.lanes[i].queued = false;
                started.push(self.lanes[i].id);
            }
        }
        started
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(board: &mut LaneBoard, peer: &str) -> LaneId {
        match board.request_send(peer, peer, false).unwrap() {
            SendAdmission::Run(id) => id,
            SendAdmission::Queue(_) => panic!("send to {} was queued", peer),
        }
    }

    fn queue(board: &mut LaneBoard, peer: &str) -> LaneId {
        match board.request_send(peer, peer, false).unwrap() {
            SendAdmission::Queue(id) => id,
            SendAdmission::Run(_) => panic!("send to {} was not queued", peer),
        }
    }

    /// The case the feature exists for: a long send to one device must not hold up a short
    /// one to another.
    #[test]
    fn sends_to_different_peers_run_side_by_side() {
        let mut board = LaneBoard::new();
        run(&mut board, "desktop");
        run(&mut board, "phone");
        assert_eq!(board.running(), 2);
    }

    #[test]
    fn a_second_send_to_the_same_peer_waits_for_the_first() {
        let mut board = LaneBoard::new();
        let first = run(&mut board, "desktop");
        let second = queue(&mut board, "desktop");
        let third = queue(&mut board, "desktop");
        assert_eq!(board.running(), 1);
        // One at a time, in the order asked.
        assert_eq!(board.finish(first), vec![second]);
        assert_eq!(board.finish(second), vec![third]);
        assert_eq!(board.finish(third), Vec::<LaneId>::new());
        assert!(!board.is_active());
    }

    #[test]
    fn the_fifth_send_waits_for_a_free_lane() {
        let mut board = LaneBoard::new();
        let ids: Vec<LaneId> = ["a", "b", "c", "d"].iter().map(|p| run(&mut board, p)).collect();
        let fifth = queue(&mut board, "e");
        let sixth = queue(&mut board, "f");
        assert_eq!(board.running(), MAX_LANES);
        assert_eq!(board.finish(ids[2]), vec![fifth]);
        assert_eq!(board.running(), MAX_LANES);
        assert_eq!(board.finish(ids[0]), vec![sixth]);
    }

    #[test]
    fn a_send_queues_behind_a_receive_from_the_same_peer() {
        let mut board = LaneBoard::new();
        let incoming = board.admit_receive("phone", "phone", false).unwrap();
        let outgoing = queue(&mut board, "phone");
        assert_eq!(board.finish(incoming), vec![outgoing]);
    }

    /// Only the sender queues: a device already sending to a peer still takes what that
    /// peer sends back.
    #[test]
    fn a_receive_from_a_peer_with_a_lane_is_accepted() {
        let mut board = LaneBoard::new();
        run(&mut board, "phone");
        assert!(board.admit_receive("phone", "phone", false).is_ok());
        assert_eq!(board.running(), 2);
    }

    #[test]
    fn a_receive_is_refused_when_every_lane_is_running() {
        let mut board = LaneBoard::new();
        for peer in ["a", "b", "c", "d"] {
            run(&mut board, peer);
        }
        assert_eq!(
            board.admit_receive("e", "e", false),
            Err(LaneRefusal::Full)
        );
        // Queued sends do not take a lane away from an incoming transfer.
        let mut board = LaneBoard::new();
        run(&mut board, "a");
        queue(&mut board, "a");
        queue(&mut board, "a");
        queue(&mut board, "a");
        assert!(board.admit_receive("b", "b", false).is_ok());
    }

    #[test]
    fn the_primary_slot_shuts_lanes_out() {
        let mut board = LaneBoard::new();
        assert_eq!(
            board.request_send("a", "a", true),
            Err(LaneRefusal::PrimaryBusy)
        );
        assert_eq!(
            board.admit_receive("a", "a", true),
            Err(LaneRefusal::PrimaryBusy)
        );
        assert!(!board.is_active());
    }

    /// The other half of the exclusivity: the primary slot asks `is_active`, and a send
    /// that is only waiting must already count.
    #[test]
    fn a_queued_send_keeps_the_primary_slot_out() {
        let mut board = LaneBoard::new();
        let first = run(&mut board, "a");
        let second = queue(&mut board, "a");
        assert!(board.is_active());
        board.finish(first);
        assert!(board.is_active());
        board.finish(second);
        assert!(!board.is_active());
    }

    #[test]
    fn cancelling_a_queued_send_starts_nothing_and_frees_nothing() {
        let mut board = LaneBoard::new();
        let first = run(&mut board, "a");
        let second = queue(&mut board, "a");
        let third = queue(&mut board, "a");
        assert_eq!(board.finish(second), Vec::<LaneId>::new());
        assert_eq!(board.running(), 1);
        assert_eq!(board.finish(first), vec![third]);
    }

    /// A lane that frees up must go to the oldest send that can use it, and a send blocked
    /// only by its own peer must not block the ones behind it.
    #[test]
    fn a_blocked_send_does_not_hold_up_the_queue() {
        let mut board = LaneBoard::new();
        let a = run(&mut board, "a");
        let b = run(&mut board, "b");
        let c = run(&mut board, "c");
        let d = run(&mut board, "d");
        let a2 = queue(&mut board, "a");
        let e = queue(&mut board, "e");
        // b ends: a2 is still behind a, so the lane goes to e.
        assert_eq!(board.finish(b), vec![e]);
        assert_eq!(board.finish(a), vec![a2]);
        let _ = (c, d);
    }
}
