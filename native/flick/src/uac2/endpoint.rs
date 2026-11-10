use crate::uac2::error::Uac2Error;
use crate::uac2::stream_config::StreamConfig;
use rusb::{Device, DeviceHandle, Direction, SyncType, TransferType, UsageType, UsbContext};

const ENDPOINT_DIRECTION_OUT: u8 = 0x00;
const ENDPOINT_TYPE_ISOCHRONOUS: u8 = 0x01;

#[derive(Debug, Clone)]
pub struct EndpointDescriptor {
    pub address: u8,
    pub attributes: u8,
    pub max_packet_size: u16,
    pub interval: u8,
}

impl EndpointDescriptor {
    pub fn is_isochronous(&self) -> bool {
        (self.attributes & 0x03) == ENDPOINT_TYPE_ISOCHRONOUS
    }

    pub fn is_output(&self) -> bool {
        (self.address & 0x80) == ENDPOINT_DIRECTION_OUT
    }

    pub fn direction(&self) -> Direction {
        if self.is_output() {
            Direction::Out
        } else {
            Direction::In
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub(crate) struct FeedbackEndpointCandidate {
    pub alt_setting: u8,
    pub address: u8,
    pub transfer_type: TransferType,
    pub usage_type: UsageType,
    pub interval: u8,
    pub max_packet_size: u16,
}

/// Select explicit feedback within one interface, only from the data endpoint's
/// selected alternate setting. Repeated addresses in inactive alts are not ambiguity.
pub(crate) fn select_explicit_feedback_endpoint(
    selected_alt: u8,
    synch_address: u8,
    sync_type: SyncType,
    endpoints: impl IntoIterator<Item = FeedbackEndpointCandidate>,
) -> Result<Option<FeedbackEndpointCandidate>, Vec<u8>> {
    let endpoints: Vec<_> = endpoints
        .into_iter()
        .filter(|endpoint| {
            endpoint.alt_setting == selected_alt
                && (endpoint.address & 0x80) != 0
                && matches!(
                    endpoint.transfer_type,
                    TransferType::Isochronous | TransferType::Interrupt
                )
                && endpoint.interval > 0
                && (endpoint.max_packet_size & 0x07ff) >= 3
        })
        .collect();

    // UAC1 and some UAC2 devices link the feedback endpoint explicitly.
    if synch_address != 0 {
        if let Some(endpoint) = endpoints
            .iter()
            .find(|endpoint| endpoint.address == synch_address)
        {
            return Ok(Some(*endpoint));
        }
    }

    if sync_type != SyncType::Asynchronous {
        return Ok(None);
    }

    // rusb maps usage 0b01 to Feedback, but 0b10 (FeedbackData) carries
    // implicit-feedback audio, not a fixed-point explicit clock report.
    let candidates: Vec<_> = endpoints
        .into_iter()
        .filter(|endpoint| endpoint.usage_type == UsageType::Feedback)
        .collect();
    match candidates.as_slice() {
        [] => Ok(None),
        [endpoint] => Ok(Some(*endpoint)),
        _ => Err(candidates.iter().map(|endpoint| endpoint.address).collect()),
    }
}

pub struct EndpointManager;

impl EndpointManager {
    pub fn find_audio_endpoint<T: UsbContext>(
        device: &Device<T>,
        interface_number: u8,
        alt_setting: u8,
    ) -> Result<EndpointDescriptor, Uac2Error> {
        let config_desc = device.active_config_descriptor()?;

        for interface in config_desc.interfaces() {
            if interface.number() != interface_number {
                continue;
            }

            for descriptor in interface.descriptors() {
                if descriptor.setting_number() != alt_setting {
                    continue;
                }

                for endpoint in descriptor.endpoint_descriptors() {
                    if endpoint.transfer_type() == TransferType::Isochronous {
                        let attributes = match endpoint.transfer_type() {
                            TransferType::Isochronous => ENDPOINT_TYPE_ISOCHRONOUS,
                            _ => 0,
                        };

                        return Ok(EndpointDescriptor {
                            address: endpoint.address(),
                            attributes,
                            max_packet_size: endpoint.max_packet_size(),
                            interval: endpoint.interval(),
                        });
                    }
                }
            }
        }

        Err(Uac2Error::EndpointNotFound)
    }

    pub fn configure_endpoint<T: UsbContext>(
        handle: &DeviceHandle<T>,
        interface_number: u8,
        alt_setting: u8,
    ) -> Result<(), Uac2Error> {
        handle.set_alternate_setting(interface_number, alt_setting)?;
        Ok(())
    }

    pub fn validate_endpoint(
        endpoint: &EndpointDescriptor,
        config: &StreamConfig,
    ) -> Result<(), Uac2Error> {
        if !endpoint.is_isochronous() {
            return Err(Uac2Error::InvalidEndpoint(
                "endpoint is not isochronous".to_string(),
            ));
        }

        if endpoint.max_packet_size < config.packet_size as u16 {
            return Err(Uac2Error::InvalidEndpoint(format!(
                "endpoint max packet size {} is less than required {}",
                endpoint.max_packet_size, config.packet_size
            )));
        }

        Ok(())
    }
}

pub struct EndpointSelector;

impl EndpointSelector {
    pub fn select_best<T: UsbContext>(
        device: &Device<T>,
        interface_number: u8,
        config: &StreamConfig,
    ) -> Result<(EndpointDescriptor, u8), Uac2Error> {
        let config_desc = device.active_config_descriptor()?;

        let mut candidates = Vec::new();

        for interface in config_desc.interfaces() {
            if interface.number() != interface_number {
                continue;
            }

            for descriptor in interface.descriptors() {
                for endpoint in descriptor.endpoint_descriptors() {
                    if endpoint.transfer_type() == TransferType::Isochronous
                        && endpoint.direction() == Direction::Out
                    {
                        let attributes = match endpoint.transfer_type() {
                            TransferType::Isochronous => ENDPOINT_TYPE_ISOCHRONOUS,
                            _ => 0,
                        };

                        let ep_desc = EndpointDescriptor {
                            address: endpoint.address(),
                            attributes,
                            max_packet_size: endpoint.max_packet_size(),
                            interval: endpoint.interval(),
                        };

                        if EndpointManager::validate_endpoint(&ep_desc, config).is_ok() {
                            candidates.push((ep_desc, descriptor.setting_number()));
                        }
                    }
                }
            }
        }

        candidates
            .into_iter()
            .max_by_key(|(ep, _)| ep.max_packet_size)
            .ok_or(Uac2Error::EndpointNotFound)
    }
}
