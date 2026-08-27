//! Predictable workload generation for the instant-payment-system load tests.

pub mod causal;
pub mod clock;
pub mod http2;
pub mod notification;
pub mod original;
pub mod pacer;
pub mod payload;
pub mod payment_state;
pub mod phase_tracker;
pub mod planner;
pub mod pull;
pub mod recorder;
pub mod replay;
pub mod replay_task;
pub mod simulator;

pub mod notification_proto {
    tonic::include_proto!("notification");
}
