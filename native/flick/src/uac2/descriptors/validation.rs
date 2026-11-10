use super::types::*;
use crate::uac2::error::Uac2Error;

pub fn validate_iad(iad: &Iad) -> Result<(), Uac2Error> {
    if iad.b_interface_count == 0 {
        return Err(Uac2Error::InvalidDescriptor(
            "b_interface_count must be > 0".to_string(),
        ));
    }
    Ok(())
}

pub fn validate_ac_interface_header(h: &AcInterfaceHeader) -> Result<(), Uac2Error> {
    if h.w_total_length == 0 {
        return Err(Uac2Error::InvalidDescriptor(
            "w_total_length must be > 0".to_string(),
        ));
    }
    Ok(())
}

pub fn validate_input_terminal(t: &InputTerminal) -> Result<(), Uac2Error> {
    if t.b_terminal_id == 0 {
        return Err(Uac2Error::InvalidDescriptor(
            "b_terminal_id must be non-zero".to_string(),
        ));
    }
    Ok(())
}

pub fn validate_output_terminal(t: &OutputTerminal) -> Result<(), Uac2Error> {
    if t.b_terminal_id == 0 {
        return Err(Uac2Error::InvalidDescriptor(
            "b_terminal_id must be non-zero".to_string(),
        ));
    }
    Ok(())
}

pub fn validate_feature_unit(f: &FeatureUnit) -> Result<(), Uac2Error> {
    if f.b_unit_id == 0 {
        return Err(Uac2Error::InvalidDescriptor(
            "b_unit_id must be non-zero".to_string(),
        ));
    }
    if !(1..=4).contains(&f.b_control_size) || f.bma_controls.is_empty() {
        return Err(Uac2Error::InvalidDescriptor(
            "feature unit must have 1..=4 byte controls and a master entry".to_string(),
        ));
    }
    Ok(())
}

pub fn validate_as_interface_general(g: &AsInterfaceGeneral) -> Result<(), Uac2Error> {
    if g.w_format_tag == 0 {
        return Err(Uac2Error::InvalidDescriptor(
            "w_format_tag must be non-zero".to_string(),
        ));
    }
    Ok(())
}

pub fn validate_format_type_i(f: &FormatTypeI) -> Result<(), Uac2Error> {
    if f.b_subslot_size == 0 || f.b_bit_resolution == 0 {
        return Err(Uac2Error::InvalidDescriptor(
            "b_subslot_size and b_bit_resolution must be non-zero".to_string(),
        ));
    }
    Ok(())
}

pub fn validate_format_type_ii(f: &FormatTypeII) -> Result<(), Uac2Error> {
    if f.w_max_bit_rate == 0 || f.w_samples_per_frame == 0 {
        return Err(Uac2Error::InvalidDescriptor(
            "w_max_bit_rate and w_samples_per_frame must be non-zero".to_string(),
        ));
    }
    Ok(())
}

pub fn validate_format_type_iii(f: &FormatTypeIII) -> Result<(), Uac2Error> {
    if f.b_subslot_size == 0 || f.b_bit_resolution == 0 {
        return Err(Uac2Error::InvalidDescriptor(
            "b_subslot_size and b_bit_resolution must be non-zero".to_string(),
        ));
    }
    Ok(())
}
