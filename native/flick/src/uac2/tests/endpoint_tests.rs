use crate::uac2::endpoint::{select_explicit_feedback_endpoint, FeedbackEndpointCandidate};
use rusb::{SyncType, TransferType, UsageType};

fn feedback(alt_setting: u8, address: u8) -> FeedbackEndpointCandidate {
    FeedbackEndpointCandidate {
        alt_setting,
        address,
        transfer_type: TransferType::Isochronous,
        usage_type: UsageType::Feedback,
        interval: 4,
        max_packet_size: 4,
    }
}

#[test]
fn dawn_pro_feedback_repeated_across_alts_is_unambiguous() {
    // The hardware dump has the same 0x81 descriptor in alternate settings
    // 1..4 and bSynchAddress=0. Only the selected alt contributes candidates.
    let endpoints: Vec<_> = (1..=4).map(|alt| feedback(alt, 0x81)).collect();
    for selected_alt in 1..=4 {
        let selected = select_explicit_feedback_endpoint(
            selected_alt,
            0,
            SyncType::Asynchronous,
            endpoints.iter().copied(),
        )
        .unwrap()
        .unwrap();
        assert_eq!(selected.alt_setting, selected_alt);
        assert_eq!(selected.address, 0x81);
        assert_eq!(selected.interval, 4);
        assert_eq!(selected.max_packet_size, 4);
    }
}

#[test]
fn feedback_does_not_borrow_an_inactive_alt_endpoint() {
    for synch_address in [0, 0x81] {
        assert!(select_explicit_feedback_endpoint(
            2,
            synch_address,
            SyncType::Asynchronous,
            [feedback(1, 0x81), feedback(3, 0x81)],
        )
        .unwrap()
        .is_none());
    }
}

#[test]
fn feedback_refuses_two_endpoints_in_the_selected_alt() {
    let ambiguous = select_explicit_feedback_endpoint(
        2,
        0,
        SyncType::Asynchronous,
        [feedback(2, 0x81), feedback(2, 0x82)],
    )
    .unwrap_err();
    assert_eq!(ambiguous, vec![0x81, 0x82]);
}

#[test]
fn feedback_prefers_an_explicit_synch_address() {
    let mut linked = feedback(2, 0x82);
    linked.usage_type = UsageType::Data;
    let selected = select_explicit_feedback_endpoint(
        2,
        0x82,
        SyncType::Asynchronous,
        [feedback(2, 0x81), linked],
    )
    .unwrap()
    .unwrap();
    assert_eq!(selected.address, 0x82);
}

#[test]
fn implicit_feedback_audio_is_not_an_explicit_clock_report() {
    let mut implicit = feedback(2, 0x82);
    implicit.usage_type = UsageType::FeedbackData;
    assert!(
        select_explicit_feedback_endpoint(2, 0, SyncType::Asynchronous, [implicit])
            .unwrap()
            .is_none()
    );

    let selected = select_explicit_feedback_endpoint(
        2,
        0,
        SyncType::Asynchronous,
        [implicit, feedback(2, 0x81)],
    )
    .unwrap()
    .unwrap();
    assert_eq!(selected.address, 0x81);
}

#[test]
fn feedback_rejects_invalid_direction_type_interval_and_size() {
    let mut wrong_type = feedback(2, 0x81);
    wrong_type.transfer_type = TransferType::Bulk;
    let mut zero_interval = feedback(2, 0x81);
    zero_interval.interval = 0;
    let mut short_report = feedback(2, 0x81);
    short_report.max_packet_size = 2;
    for invalid in [feedback(2, 0x01), wrong_type, zero_interval, short_report] {
        assert!(
            select_explicit_feedback_endpoint(2, 0, SyncType::Asynchronous, [invalid])
                .unwrap()
                .is_none()
        );
    }
}

#[test]
fn feedback_usage_fallback_requires_an_asynchronous_sink() {
    for sync in [SyncType::Adaptive, SyncType::Synchronous, SyncType::NoSync] {
        assert!(
            select_explicit_feedback_endpoint(2, 0, sync, [feedback(2, 0x81)])
                .unwrap()
                .is_none()
        );
    }
}
