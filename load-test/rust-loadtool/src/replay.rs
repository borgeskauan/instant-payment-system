use anyhow::{Result, bail};

const BLOCK_SIZE: u64 = 100;
const GAMMA: u64 = 0x9e37_79b9_7f4a_7c15;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ReplayDomain {
    Scenario,
    Pacs008,
    Pacs002,
}

impl ReplayDomain {
    const fn value(self) -> u64 {
        match self {
            Self::Scenario => 0x5343_454e_4152_494f,
            Self::Pacs008 => 0x5041_4353_3030_3800,
            Self::Pacs002 => 0x5041_4353_3030_3200,
        }
    }
}

#[derive(Debug)]
pub struct ReplaySelector {
    quota: u64,
    domain: ReplayDomain,
}

impl ReplaySelector {
    pub fn new(share: f64, domain: ReplayDomain) -> Result<Self> {
        let exact_quota = share * BLOCK_SIZE as f64;
        let quota = exact_quota.round();
        if !(share > 0.0
            && share <= 1.0
            && (exact_quota - quota).abs() <= f64::EPSILON * BLOCK_SIZE as f64)
        {
            bail!("replay share must select a whole percentage in (0, 1]");
        }
        Ok(Self {
            quota: quota as u64,
            domain,
        })
    }

    pub fn selected(&self, ordinal: u64) -> bool {
        let block = ordinal / BLOCK_SIZE;
        let position = ordinal % BLOCK_SIZE;
        let rotation = stable_rotation(self.domain, block);
        ((position * 37 + rotation) % BLOCK_SIZE) < self.quota
    }
}

pub fn stable_rotation(domain: ReplayDomain, block: u64) -> u64 {
    splitmix64(domain.value() ^ block.wrapping_mul(GAMMA)) % BLOCK_SIZE
}

fn splitmix64(mut value: u64) -> u64 {
    value = value.wrapping_add(GAMMA);
    value = (value ^ (value >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
    value = (value ^ (value >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
    value ^ (value >> 31)
}
