use crate::media::{
    PreparedRawResource, ResourcePreparationProblem, ResourcePreparationProblemCode,
    ResourcePreparationProfile, ensure_lease_active, verify_resource_media,
};
use renderweave_renderer_document::{
    AdmittedRenderResource, FontFlavor, RenderResourceKind, RenderResourceMediaType,
};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::fmt::{Debug, Formatter};
use std::sync::Arc;

pub const MAX_REQUEST_UNIQUE_FONTS: usize = 32;
pub const REQUEST_UNIQUE_FONTS_LIMIT_ID: &str = "layoutFontAndRaster.uniqueFonts";
pub const MAX_FONT_TABLES_PER_CONTENT: usize = 256;
pub const FONT_TABLES_PER_CONTENT_LIMIT_ID: &str = "layoutFontAndRaster.tablesPerFont";
pub const MAX_REQUEST_FONT_TABLES: usize = 4_096;
pub const REQUEST_FONT_TABLES_LIMIT_ID: &str = "layoutFontAndRaster.fontTablesTotal";

const CFF_MAX_STACK: usize = 48;
const CFF_MAX_SUBR_DEPTH: usize = 10;
const FONT_FACTS_DIGEST_DOMAIN: &[u8] = b"renderweave-font-prepared-facts/1.0\0";

#[derive(Eq, Ord, PartialEq, PartialOrd)]
struct PreparedFontCacheKey {
    profile: ResourcePreparationProfile,
    kind: RenderResourceKind,
    sha256: Box<str>,
    byte_length: u64,
    media_type: RenderResourceMediaType,
}

impl PreparedFontCacheKey {
    fn new(resource: &AdmittedRenderResource, profile: ResourcePreparationProfile) -> Self {
        Self {
            profile,
            kind: resource.kind(),
            sha256: resource.sha256().into(),
            byte_length: resource.byte_length(),
            media_type: resource.media_type(),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
struct PreparedFontFacts {
    flavor: FontFlavor,
    units_per_em: u16,
    table_count: u16,
    glyph_count: u16,
    non_empty_outline_count: u16,
    supported_cmap_subtable_count: u16,
    layout_table_mask: u8,
}

impl PreparedFontFacts {
    fn digest(self) -> [u8; 32] {
        let mut digest = Sha256::new();
        digest.update(FONT_FACTS_DIGEST_DOMAIN);
        digest.update(match self.flavor {
            FontFlavor::TrueTypeGlyf => b"TRUETYPE_GLYF".as_slice(),
            FontFlavor::Cff => b"CFF".as_slice(),
        });
        digest.update(self.units_per_em.to_be_bytes());
        digest.update(self.table_count.to_be_bytes());
        digest.update(self.glyph_count.to_be_bytes());
        digest.update(self.non_empty_outline_count.to_be_bytes());
        digest.update(self.supported_cmap_subtable_count.to_be_bytes());
        digest.update([self.layout_table_mask]);
        digest.finalize().into()
    }
}

struct PreparedFontContent {
    source_bytes: Arc<[u8]>,
    source_sha256: [u8; 32],
    facts: PreparedFontFacts,
    facts_sha256: [u8; 32],
}

impl PreparedFontContent {
    fn new(source_bytes: Arc<[u8]>, facts: PreparedFontFacts) -> Self {
        Self {
            source_sha256: Sha256::digest(&source_bytes).into(),
            facts_sha256: facts.digest(),
            source_bytes,
            facts,
        }
    }

    fn is_intact(&self, raw: &PreparedRawResource) -> bool {
        self.source_bytes.as_ref() == raw.bytes()
            && <[u8; 32]>::from(Sha256::digest(&self.source_bytes)) == self.source_sha256
            && self.facts.digest() == self.facts_sha256
    }
}

#[derive(Clone)]
pub struct PreparedFontResource {
    resource_id: Box<str>,
    content: Arc<PreparedFontContent>,
    cache_hit: bool,
}

impl PreparedFontResource {
    pub fn resource_id(&self) -> &str {
        &self.resource_id
    }

    pub fn exact_bytes(&self) -> &[u8] {
        &self.content.source_bytes
    }

    pub fn flavor(&self) -> FontFlavor {
        self.content.facts.flavor
    }

    pub fn units_per_em(&self) -> u16 {
        self.content.facts.units_per_em
    }

    pub fn table_count(&self) -> u16 {
        self.content.facts.table_count
    }

    pub fn glyph_count(&self) -> u16 {
        self.content.facts.glyph_count
    }

    pub fn non_empty_outline_count(&self) -> u16 {
        self.content.facts.non_empty_outline_count
    }

    pub fn supported_cmap_subtable_count(&self) -> u16 {
        self.content.facts.supported_cmap_subtable_count
    }

    pub fn has_gdef(&self) -> bool {
        self.content.facts.layout_table_mask & 0b0001 != 0
    }

    pub fn has_gsub(&self) -> bool {
        self.content.facts.layout_table_mask & 0b0010 != 0
    }

    pub fn has_gpos(&self) -> bool {
        self.content.facts.layout_table_mask & 0b0100 != 0
    }

    pub fn has_kern(&self) -> bool {
        self.content.facts.layout_table_mask & 0b1000 != 0
    }

    pub fn cache_hit(&self) -> bool {
        self.cache_hit
    }
}

impl Debug for PreparedFontResource {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("PreparedFontResource")
            .field("resource_id", &self.resource_id)
            .field("flavor", &self.content.facts.flavor)
            .field("units_per_em", &self.content.facts.units_per_em)
            .field("table_count", &self.content.facts.table_count)
            .field("glyph_count", &self.content.facts.glyph_count)
            .field(
                "non_empty_outline_count",
                &self.content.facts.non_empty_outline_count,
            )
            .field(
                "supported_cmap_subtable_count",
                &self.content.facts.supported_cmap_subtable_count,
            )
            .field("cache_hit", &self.cache_hit)
            .finish()
    }
}

#[derive(Debug, Default)]
struct PreparedFontBudget {
    consumed_unique_fonts: usize,
    retained_table_count: usize,
}

impl PreparedFontBudget {
    fn ensure_can_reserve(
        &self,
        resource: &AdmittedRenderResource,
        table_count: usize,
    ) -> Result<(), ResourcePreparationProblem> {
        if table_count > MAX_FONT_TABLES_PER_CONTENT {
            return Err(ResourcePreparationProblem::budget_for_limit(
                resource.resource_id(),
                FONT_TABLES_PER_CONTENT_LIMIT_ID,
            ));
        }
        if self.consumed_unique_fonts >= MAX_REQUEST_UNIQUE_FONTS {
            return Err(ResourcePreparationProblem::budget_for_limit(
                resource.resource_id(),
                REQUEST_UNIQUE_FONTS_LIMIT_ID,
            ));
        }
        let Some(next_tables) = self.retained_table_count.checked_add(table_count) else {
            return Err(ResourcePreparationProblem::budget_for_limit(
                resource.resource_id(),
                REQUEST_FONT_TABLES_LIMIT_ID,
            ));
        };
        if next_tables > MAX_REQUEST_FONT_TABLES {
            return Err(ResourcePreparationProblem::budget_for_limit(
                resource.resource_id(),
                REQUEST_FONT_TABLES_LIMIT_ID,
            ));
        }
        Ok(())
    }

    fn commit(&mut self, table_count: usize) {
        self.consumed_unique_fonts += 1;
        self.retained_table_count += table_count;
    }
}

#[derive(Default)]
pub struct RequestPreparedFontCache {
    entries: BTreeMap<PreparedFontCacheKey, Arc<PreparedFontContent>>,
    budget: PreparedFontBudget,
}

impl RequestPreparedFontCache {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn unique_content_count(&self) -> usize {
        self.entries.len()
    }

    pub fn retained_table_count(&self) -> usize {
        self.budget.retained_table_count
    }

    pub fn prepare_or_lookup(
        &mut self,
        resource: &AdmittedRenderResource,
        profile: ResourcePreparationProfile,
        raw: &PreparedRawResource,
        now_epoch_millis: i64,
    ) -> Result<PreparedFontResource, ResourcePreparationProblem> {
        ensure_lease_active(resource, now_epoch_millis)?;
        if raw.resource_id() != resource.resource_id() || raw.profile() != profile {
            return Err(internal_problem(resource));
        }
        let media = verify_resource_media(resource, raw.bytes())?;
        if raw.media() != &media || resource.kind() != RenderResourceKind::Font {
            return Err(internal_problem(resource));
        }

        let key = PreparedFontCacheKey::new(resource, profile);
        if let Some(content) = self.entries.get(&key).cloned() {
            if content.is_intact(raw) {
                return Ok(PreparedFontResource {
                    resource_id: resource.resource_id().into(),
                    content,
                    cache_hit: true,
                });
            }
            self.entries.remove(&key);
            return Err(internal_problem(resource));
        }

        let table_count = font_table_count(raw.bytes()).ok_or_else(|| decode_problem(resource))?;
        self.budget
            .ensure_can_reserve(resource, usize::from(table_count))?;
        let expected_flavor =
            expected_font_flavor(resource).ok_or_else(|| internal_problem(resource))?;
        let facts = parse_font_program(raw.bytes(), expected_flavor)
            .map_err(|()| decode_problem(resource))?;
        if facts.table_count != table_count
            || resource.technical_descriptor().font_metrics()
                != Some((facts.flavor, facts.units_per_em))
        {
            return Err(internal_problem(resource));
        }

        let content = Arc::new(PreparedFontContent::new(raw.shared_bytes(), facts));
        self.budget.commit(usize::from(table_count));
        self.entries.insert(key, Arc::clone(&content));
        Ok(PreparedFontResource {
            resource_id: resource.resource_id().into(),
            content,
            cache_hit: false,
        })
    }
}

impl Debug for RequestPreparedFontCache {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("RequestPreparedFontCache")
            .field("unique_content_count", &self.entries.len())
            .field("consumed_unique_fonts", &self.budget.consumed_unique_fonts)
            .field("retained_table_count", &self.budget.retained_table_count)
            .finish()
    }
}

fn expected_font_flavor(resource: &AdmittedRenderResource) -> Option<FontFlavor> {
    match resource.media_type() {
        RenderResourceMediaType::FontTtf => Some(FontFlavor::TrueTypeGlyf),
        RenderResourceMediaType::FontOtf => Some(FontFlavor::Cff),
        RenderResourceMediaType::ImagePng
        | RenderResourceMediaType::ImageJpeg
        | RenderResourceMediaType::ImageWebp => None,
    }
}

fn internal_problem(resource: &AdmittedRenderResource) -> ResourcePreparationProblem {
    ResourcePreparationProblem::for_resource(
        ResourcePreparationProblemCode::RenderInternalError,
        resource.resource_id(),
    )
}

fn decode_problem(resource: &AdmittedRenderResource) -> ResourcePreparationProblem {
    ResourcePreparationProblem::for_resource(
        ResourcePreparationProblemCode::DecodeFailed,
        resource.resource_id(),
    )
}

#[derive(Clone, Copy)]
struct FontTable {
    offset: usize,
    length: usize,
}

fn font_table_count(bytes: &[u8]) -> Option<u16> {
    if bytes.len() < 12 {
        return None;
    }
    be_u16(bytes, 4)
}

fn parse_font_program(bytes: &[u8], expected_flavor: FontFlavor) -> Result<PreparedFontFacts, ()> {
    if bytes.len() < 12 {
        return Err(());
    }
    let actual_flavor = match &bytes[..4] {
        [0, 1, 0, 0] => FontFlavor::TrueTypeGlyf,
        b"OTTO" => FontFlavor::Cff,
        _ => return Err(()),
    };
    if actual_flavor != expected_flavor {
        return Err(());
    }

    let table_count = font_table_count(bytes).ok_or(())?;
    let table_count_usize = usize::from(table_count);
    let directory_end = 12usize
        .checked_add(table_count_usize.checked_mul(16).ok_or(())?)
        .ok_or(())?;
    if table_count_usize == 0
        || table_count_usize > MAX_FONT_TABLES_PER_CONTENT
        || directory_end > bytes.len()
    {
        return Err(());
    }

    let mut tables = BTreeMap::<[u8; 4], FontTable>::new();
    let mut occupied = Vec::with_capacity(table_count_usize);
    for index in 0..table_count_usize {
        let base = 12 + index * 16;
        let tag: [u8; 4] = bytes[base..base + 4].try_into().map_err(|_| ())?;
        let offset = usize::try_from(be_u32(bytes, base + 8).ok_or(())?).map_err(|_| ())?;
        let length = usize::try_from(be_u32(bytes, base + 12).ok_or(())?).map_err(|_| ())?;
        let end = offset.checked_add(length).ok_or(())?;
        if length == 0 || offset < directory_end || offset % 4 != 0 || end > bytes.len() {
            return Err(());
        }
        if tables.insert(tag, FontTable { offset, length }).is_some() {
            return Err(());
        }
        occupied.push((offset, end));
    }
    occupied.sort_unstable();
    if occupied.windows(2).any(|pair| pair[0].1 > pair[1].0) {
        return Err(());
    }

    let head = table(&tables, b"head")?;
    if head.length < 54 {
        return Err(());
    }
    let units_per_em = be_u16(bytes, head.offset + 18).ok_or(())?;
    if !(16..=16_384).contains(&units_per_em) {
        return Err(());
    }
    let index_to_loc_format = be_u16(bytes, head.offset + 50).ok_or(())?;

    let maxp = table(&tables, b"maxp")?;
    if maxp.length < 6 {
        return Err(());
    }
    let maxp_version = be_u32(bytes, maxp.offset).ok_or(())?;
    let glyph_count = be_u16(bytes, maxp.offset + 4).ok_or(())?;
    if glyph_count == 0 {
        return Err(());
    }

    let non_empty_outline_count = match actual_flavor {
        FontFlavor::TrueTypeGlyf => {
            if maxp_version != 0x0001_0000 {
                return Err(());
            }
            validate_glyf_program(
                bytes,
                &tables,
                usize::from(glyph_count),
                index_to_loc_format,
            )?
        }
        FontFlavor::Cff => {
            if maxp_version != 0x0000_5000 {
                return Err(());
            }
            let cff = table(&tables, b"CFF ")?;
            validate_cff(bytes, cff, usize::from(glyph_count))?;
            glyph_count
        }
    };

    let cmap = table(&tables, b"cmap")?;
    let supported_cmap_subtable_count = validate_cmap(bytes, cmap)?;
    let layout_table_mask = u8::from(tables.contains_key(b"GDEF"))
        | (u8::from(tables.contains_key(b"GSUB")) << 1)
        | (u8::from(tables.contains_key(b"GPOS")) << 2)
        | (u8::from(tables.contains_key(b"kern")) << 3);

    Ok(PreparedFontFacts {
        flavor: actual_flavor,
        units_per_em,
        table_count,
        glyph_count,
        non_empty_outline_count,
        supported_cmap_subtable_count,
        layout_table_mask,
    })
}

fn table(tables: &BTreeMap<[u8; 4], FontTable>, tag: &[u8; 4]) -> Result<FontTable, ()> {
    tables.get(tag).copied().ok_or(())
}

fn validate_glyf_program(
    bytes: &[u8],
    tables: &BTreeMap<[u8; 4], FontTable>,
    glyph_count: usize,
    index_to_loc_format: u16,
) -> Result<u16, ()> {
    let loca = table(tables, b"loca")?;
    let glyf = table(tables, b"glyf")?;
    let entry_count = glyph_count.checked_add(1).ok_or(())?;
    let entry_size = match index_to_loc_format {
        0 => 2,
        1 => 4,
        _ => return Err(()),
    };
    if loca.length != entry_count.checked_mul(entry_size).ok_or(())? {
        return Err(());
    }

    let mut offsets = Vec::with_capacity(entry_count);
    for index in 0..entry_count {
        let position = loca
            .offset
            .checked_add(index.checked_mul(entry_size).ok_or(())?)
            .ok_or(())?;
        let value = if entry_size == 2 {
            usize::from(be_u16(bytes, position).ok_or(())?)
                .checked_mul(2)
                .ok_or(())?
        } else {
            usize::try_from(be_u32(bytes, position).ok_or(())?).map_err(|_| ())?
        };
        if value > glyf.length || offsets.last().is_some_and(|previous| value < *previous) {
            return Err(());
        }
        offsets.push(value);
    }
    if offsets.last().copied() != Some(glyf.length) {
        return Err(());
    }

    let mut composite_references = vec![None; glyph_count];
    let mut non_empty = 0_u16;
    for glyph in 0..glyph_count {
        let start = glyf.offset.checked_add(offsets[glyph]).ok_or(())?;
        let end = glyf.offset.checked_add(offsets[glyph + 1]).ok_or(())?;
        if start == end {
            continue;
        }
        non_empty = non_empty.checked_add(1).ok_or(())?;
        composite_references[glyph] = parse_glyph(bytes, start, end, glyph_count)?;
    }
    if !composite_graph_is_acyclic(&composite_references) {
        return Err(());
    }
    Ok(non_empty)
}

fn parse_glyph(
    bytes: &[u8],
    start: usize,
    end: usize,
    glyph_count: usize,
) -> Result<Option<Vec<usize>>, ()> {
    if end.checked_sub(start).ok_or(())? < 10 {
        return Err(());
    }
    let contour_count = be_i16(bytes, start).ok_or(())?;
    if contour_count < -1 {
        return Err(());
    }
    if contour_count == -1 {
        return parse_composite_glyph(bytes, start, end, glyph_count).map(Some);
    }

    let mut position = start + 10;
    let mut points = 0usize;
    for _ in 0..usize::try_from(contour_count).map_err(|_| ())? {
        let point = usize::from(read_u16_bounded(bytes, &mut position, end)?);
        if point < points {
            return Err(());
        }
        points = point.checked_add(1).ok_or(())?;
    }
    let instruction_length = usize::from(read_u16_bounded(bytes, &mut position, end)?);
    position = position.checked_add(instruction_length).ok_or(())?;
    if position > end {
        return Err(());
    }

    let mut flags = Vec::with_capacity(points);
    while flags.len() < points {
        if position >= end {
            return Err(());
        }
        let flag = bytes[position];
        position += 1;
        flags.push(flag);
        if flag & 0x08 != 0 {
            if position >= end {
                return Err(());
            }
            let repeat = usize::from(bytes[position]);
            position += 1;
            if flags.len().checked_add(repeat).ok_or(())? > points {
                return Err(());
            }
            flags.extend(std::iter::repeat_n(flag, repeat));
        }
    }
    for flag in &flags {
        let bytes_needed = if flag & 0x02 != 0 {
            1
        } else if flag & 0x10 == 0 {
            2
        } else {
            0
        };
        position = position.checked_add(bytes_needed).ok_or(())?;
        if position > end {
            return Err(());
        }
    }
    for flag in &flags {
        let bytes_needed = if flag & 0x04 != 0 {
            1
        } else if flag & 0x20 == 0 {
            2
        } else {
            0
        };
        position = position.checked_add(bytes_needed).ok_or(())?;
        if position > end {
            return Err(());
        }
    }
    Ok(None)
}

fn parse_composite_glyph(
    bytes: &[u8],
    start: usize,
    end: usize,
    glyph_count: usize,
) -> Result<Vec<usize>, ()> {
    let mut position = start + 10;
    let mut references = Vec::new();
    let final_flags = loop {
        let flags = read_u16_bounded(bytes, &mut position, end)?;
        let glyph_index = usize::from(read_u16_bounded(bytes, &mut position, end)?);
        if glyph_index >= glyph_count {
            return Err(());
        }
        references.push(glyph_index);
        let argument_bytes = if flags & 0x0001 != 0 { 4 } else { 2 };
        position = position.checked_add(argument_bytes).ok_or(())?;
        if position > end {
            return Err(());
        }
        let transform_bytes = if flags & 0x0008 != 0 {
            2
        } else if flags & 0x0040 != 0 {
            4
        } else if flags & 0x0080 != 0 {
            8
        } else {
            0
        };
        position = position.checked_add(transform_bytes).ok_or(())?;
        if position > end {
            return Err(());
        }
        if flags & 0x0020 == 0 {
            break flags;
        }
    };
    if final_flags & 0x0100 != 0 {
        let instruction_length = usize::from(read_u16_bounded(bytes, &mut position, end)?);
        position = position.checked_add(instruction_length).ok_or(())?;
        if position > end {
            return Err(());
        }
    }
    Ok(references)
}

fn composite_graph_is_acyclic(references: &[Option<Vec<usize>>]) -> bool {
    let mut state = vec![0_u8; references.len()];
    for root in 0..references.len() {
        if references[root].is_none() || state[root] == 2 {
            continue;
        }
        state[root] = 1;
        let mut stack = vec![(root, 0usize)];
        while let Some((glyph, next_index)) = stack.last_mut() {
            let children = references[*glyph]
                .as_ref()
                .expect("only composite glyphs enter the traversal");
            if *next_index == children.len() {
                state[*glyph] = 2;
                stack.pop();
                continue;
            }
            let child = children[*next_index];
            *next_index += 1;
            if references[child].is_none() || state[child] == 2 {
                continue;
            }
            if state[child] == 1 {
                return false;
            }
            state[child] = 1;
            stack.push((child, 0));
        }
    }
    true
}

fn validate_cmap(bytes: &[u8], cmap: FontTable) -> Result<u16, ()> {
    if cmap.length < 4 || be_u16(bytes, cmap.offset) != Some(0) {
        return Err(());
    }
    let record_count = usize::from(be_u16(bytes, cmap.offset + 2).ok_or(())?);
    let records_end = 4usize
        .checked_add(record_count.checked_mul(8).ok_or(())?)
        .ok_or(())?;
    if record_count == 0 || records_end > cmap.length {
        return Err(());
    }
    let cmap_end = cmap.offset.checked_add(cmap.length).ok_or(())?;
    let mut supported = 0_u16;
    for index in 0..record_count {
        let record = cmap.offset + 4 + index * 8;
        let subtable_offset =
            usize::try_from(be_u32(bytes, record + 4).ok_or(())?).map_err(|_| ())?;
        if subtable_offset >= cmap.length {
            return Err(());
        }
        let subtable = cmap.offset.checked_add(subtable_offset).ok_or(())?;
        let format = be_u16(bytes, subtable).ok_or(())?;
        let valid = match format {
            4 => {
                if subtable.checked_add(14).ok_or(())? > cmap_end {
                    false
                } else {
                    let seg_count_x2 = usize::from(be_u16(bytes, subtable + 6).ok_or(())?);
                    seg_count_x2 != 0
                        && seg_count_x2 % 2 == 0
                        && subtable
                            .checked_add(16)
                            .and_then(|value| {
                                seg_count_x2
                                    .checked_mul(4)
                                    .and_then(|size| value.checked_add(size))
                            })
                            .is_some_and(|end| end <= cmap_end)
                }
            }
            12 => {
                if subtable.checked_add(16).ok_or(())? > cmap_end {
                    false
                } else {
                    let groups =
                        usize::try_from(be_u32(bytes, subtable + 12).ok_or(())?).map_err(|_| ())?;
                    groups != 0
                        && 16usize
                            .checked_add(groups.checked_mul(12).ok_or(())?)
                            .is_some_and(|size| size <= cmap.length - subtable_offset)
                }
            }
            0 | 6 => subtable.checked_add(6).ok_or(())? <= cmap_end,
            _ => false,
        };
        if valid {
            supported = supported.checked_add(1).ok_or(())?;
        }
    }
    if supported == 0 {
        return Err(());
    }
    Ok(supported)
}

#[derive(Clone)]
struct CffIndex {
    count: usize,
    offsets: Vec<usize>,
    next_position: usize,
    data_offset: usize,
    data_end: usize,
}

impl CffIndex {
    fn offset_of(&self, index: usize) -> Result<usize, ()> {
        self.data_offset
            .checked_add(*self.offsets.get(index).ok_or(())?)
            .ok_or(())
    }
}

#[derive(Default)]
struct CffDict {
    char_strings_offset: Option<usize>,
    private_offset: Option<usize>,
    private_size: Option<usize>,
    subrs_offset: Option<usize>,
}

fn validate_cff(bytes: &[u8], cff: FontTable, expected_glyphs: usize) -> Result<(), ()> {
    let end = cff.offset.checked_add(cff.length).ok_or(())?;
    if cff.offset.checked_add(4).ok_or(())? > end {
        return Err(());
    }
    let major = bytes[cff.offset];
    let minor = bytes[cff.offset + 1];
    let header_size = usize::from(bytes[cff.offset + 2]);
    let off_size = bytes[cff.offset + 3];
    if major != 1
        || minor != 0
        || header_size < 4
        || !(1..=4).contains(&off_size)
        || cff.offset.checked_add(header_size).ok_or(())? > end
    {
        return Err(());
    }

    let name_index = parse_cff_index(bytes, cff.offset + header_size, end)?;
    if name_index.count != 1 {
        return Err(());
    }
    let top_dict_index = parse_cff_index(bytes, name_index.next_position, end)?;
    if top_dict_index.count != 1 {
        return Err(());
    }
    let string_index = parse_cff_index(bytes, top_dict_index.next_position, end)?;
    let global_subr_index = parse_cff_index(bytes, string_index.next_position, end)?;
    let top_dict = parse_cff_dict(
        bytes,
        top_dict_index.data_offset,
        top_dict_index.data_end,
        true,
    )?;
    let char_strings_position = cff
        .offset
        .checked_add(top_dict.char_strings_offset.ok_or(())?)
        .ok_or(())?;
    if char_strings_position >= end {
        return Err(());
    }
    let char_strings = parse_cff_index(bytes, char_strings_position, end)?;
    if char_strings.count != expected_glyphs {
        return Err(());
    }

    let mut local_subr_index = None;
    match (top_dict.private_offset, top_dict.private_size) {
        (Some(private_offset), Some(private_size)) => {
            let private_start = cff.offset.checked_add(private_offset).ok_or(())?;
            let private_end = private_start.checked_add(private_size).ok_or(())?;
            if private_start < cff.offset || private_end > end {
                return Err(());
            }
            let private_dict = parse_cff_dict(bytes, private_start, private_end, false)?;
            if let Some(subrs_offset) = private_dict.subrs_offset {
                let subrs_start = private_start.checked_add(subrs_offset).ok_or(())?;
                local_subr_index = Some(parse_cff_index(bytes, subrs_start, private_end)?);
            }
        }
        (None, None) => {}
        _ => return Err(()),
    }

    let local_count = local_subr_index.as_ref().map_or(0, |index| index.count);
    let global_count = global_subr_index.count;
    let mut active_subrs = vec![false; local_count.checked_add(global_count).ok_or(())?];
    let char_string_sources = CffCharStringSources {
        bytes,
        local_subrs: local_subr_index.as_ref(),
        global_subrs: &global_subr_index,
    };
    for glyph in 0..char_strings.count {
        validate_cff_char_string(
            &char_string_sources,
            char_strings.offset_of(glyph)?,
            char_strings.offset_of(glyph + 1)?,
            0,
            &mut active_subrs,
            false,
        )?;
    }
    Ok(())
}

fn parse_cff_index(bytes: &[u8], mut position: usize, end: usize) -> Result<CffIndex, ()> {
    let count = usize::from(read_u16_bounded(bytes, &mut position, end)?);
    if count == 0 {
        return Ok(CffIndex {
            count,
            offsets: vec![0],
            next_position: position,
            data_offset: position,
            data_end: position,
        });
    }
    if position >= end {
        return Err(());
    }
    let off_size = usize::from(bytes[position]);
    position += 1;
    let offset_count = count.checked_add(1).ok_or(())?;
    let encoded_offsets = offset_count.checked_mul(off_size).ok_or(())?;
    if !(1..=4).contains(&off_size) || position.checked_add(encoded_offsets).ok_or(())? > end {
        return Err(());
    }
    let mut offsets = Vec::with_capacity(offset_count);
    for index in 0..offset_count {
        let mut value = 0usize;
        for byte_index in 0..off_size {
            value = value
                .checked_mul(256)
                .and_then(|current| {
                    current
                        .checked_add(usize::from(bytes[position + index * off_size + byte_index]))
                })
                .ok_or(())?;
        }
        offsets.push(value.checked_sub(1).ok_or(())?);
    }
    if offsets.first().copied() != Some(0) || offsets.windows(2).any(|pair| pair[0] > pair[1]) {
        return Err(());
    }
    let data_offset = position.checked_add(encoded_offsets).ok_or(())?;
    let data_end = data_offset
        .checked_add(offsets.last().copied().ok_or(())?)
        .ok_or(())?;
    if data_end > end {
        return Err(());
    }
    Ok(CffIndex {
        count,
        offsets,
        next_position: data_end,
        data_offset,
        data_end,
    })
}

fn parse_cff_dict(bytes: &[u8], start: usize, end: usize, top_level: bool) -> Result<CffDict, ()> {
    let mut position = start;
    let mut stack = [0_i64; CFF_MAX_STACK];
    let mut stack_len = 0usize;
    let mut dict = CffDict::default();
    while position < end {
        let b0 = bytes[position];
        if b0 == 12 {
            if position + 1 >= end {
                return Err(());
            }
            let count = cff_dict_escaped_operator_count(bytes[position + 1]).ok_or(())?;
            if stack_len < count {
                return Err(());
            }
            stack_len -= count;
            position += 2;
        } else if b0 <= 21 {
            let count = cff_dict_operator_count(b0, top_level).ok_or(())?;
            if stack_len < count {
                return Err(());
            }
            if b0 == 17 && top_level {
                dict.char_strings_offset = cff_nonnegative_usize(stack[stack_len - 1]);
                if dict.char_strings_offset.is_none() {
                    return Err(());
                }
            } else if b0 == 18 && top_level {
                dict.private_size = cff_nonnegative_usize(stack[stack_len - 2]);
                dict.private_offset = cff_nonnegative_usize(stack[stack_len - 1]);
                if dict.private_size.is_none() || dict.private_offset.is_none() {
                    return Err(());
                }
            } else if b0 == 19 && !top_level {
                dict.subrs_offset = cff_nonnegative_usize(stack[stack_len - 1]);
                if dict.subrs_offset.is_none() {
                    return Err(());
                }
            }
            stack_len -= count;
            position += 1;
        } else {
            if stack_len >= CFF_MAX_STACK {
                return Err(());
            }
            let (value, next) = read_cff_number(bytes, position, end)?;
            stack[stack_len] = value;
            stack_len += 1;
            position = next;
        }
    }
    Ok(dict)
}

struct CffCharStringSources<'a> {
    bytes: &'a [u8],
    local_subrs: Option<&'a CffIndex>,
    global_subrs: &'a CffIndex,
}

fn validate_cff_char_string(
    sources: &CffCharStringSources<'_>,
    start: usize,
    end: usize,
    depth: usize,
    active_subrs: &mut [bool],
    is_subr: bool,
) -> Result<(), ()> {
    if depth > CFF_MAX_SUBR_DEPTH || start > end {
        return Err(());
    }
    let local_count = sources.local_subrs.map_or(0, |index| index.count);
    let global_count = sources.global_subrs.count;
    let mut position = start;
    let mut stack = [0_i64; CFF_MAX_STACK];
    let mut stack_len = 0usize;
    let mut width_allowed = true;
    let mut saw_endchar = false;
    let mut saw_return = false;
    while position < end {
        let b0 = sources.bytes[position];
        if b0 == 28 || b0 == 30 || (32..=254).contains(&b0) || b0 == 255 {
            if stack_len >= CFF_MAX_STACK {
                return Err(());
            }
            let (value, next) = read_cff_number(sources.bytes, position, end)?;
            stack[stack_len] = value;
            stack_len += 1;
            position = next;
            continue;
        }
        if b0 == 12 {
            if position + 1 >= end {
                return Err(());
            }
            let operator = sources.bytes[position + 1];
            let count = cff_escaped_char_string_operator_count(operator).ok_or(())?;
            if stack_len != count {
                return Err(());
            }
            if matches!(operator, 34..=37) {
                width_allowed = false;
            }
            stack_len = 0;
            position += 2;
            continue;
        }

        match b0 {
            1 | 3 | 18 | 23 => {
                if stack_len < 2 || stack_len % 2 != 0 {
                    return Err(());
                }
                stack_len = 0;
            }
            4 | 22 => {
                if !cff_clearing_operands(stack_len, 1, width_allowed) {
                    return Err(());
                }
                stack_len = 0;
                width_allowed = false;
            }
            21 => {
                if !cff_clearing_operands(stack_len, 2, width_allowed) {
                    return Err(());
                }
                stack_len = 0;
                width_allowed = false;
            }
            5..=7 => {
                if stack_len < 1 {
                    return Err(());
                }
                stack_len = 0;
                width_allowed = false;
            }
            8 => {
                if stack_len < 6 || stack_len % 6 != 0 {
                    return Err(());
                }
                stack_len = 0;
                width_allowed = false;
            }
            10 | 29 => {
                if stack_len != 1 {
                    return Err(());
                }
                let subr = usize::try_from(stack[0]).map_err(|_| ())?;
                let (index, count, slot) = if b0 == 10 {
                    (sources.local_subrs.ok_or(())?, local_count, subr)
                } else {
                    (
                        sources.global_subrs,
                        global_count,
                        local_count.checked_add(subr).ok_or(())?,
                    )
                };
                if subr >= count || slot >= active_subrs.len() || active_subrs[slot] {
                    return Err(());
                }
                active_subrs[slot] = true;
                let nested = validate_cff_char_string(
                    sources,
                    index.offset_of(subr)?,
                    index.offset_of(subr + 1)?,
                    depth + 1,
                    active_subrs,
                    true,
                );
                active_subrs[slot] = false;
                nested?;
                stack_len = 0;
            }
            11 => {
                if stack_len != 0 {
                    return Err(());
                }
                saw_return = true;
            }
            14 => {
                if stack_len != 0
                    && stack_len != 4
                    && !(width_allowed && matches!(stack_len, 1 | 5))
                {
                    return Err(());
                }
                stack_len = 0;
                saw_endchar = true;
            }
            19 | 20 => {
                let mut stems = stack_len;
                if stems % 2 != 0 {
                    if !width_allowed {
                        return Err(());
                    }
                    stems -= 1;
                }
                let mask_bytes = (stems / 2).div_ceil(8);
                position = position
                    .checked_add(1)
                    .and_then(|next| next.checked_add(mask_bytes))
                    .ok_or(())?;
                if position > end {
                    return Err(());
                }
                stack_len = 0;
                width_allowed = false;
                continue;
            }
            24 | 25 => {
                if stack_len < 8 || stack_len % 2 != 0 {
                    return Err(());
                }
                stack_len = 0;
                width_allowed = false;
            }
            26 | 27 => {
                if stack_len < 4 || !matches!(stack_len % 4, 0 | 1) {
                    return Err(());
                }
                stack_len = 0;
                width_allowed = false;
            }
            30 | 31 => {
                if stack_len < 4 || !matches!(stack_len % 8, 4 | 5) {
                    return Err(());
                }
                stack_len = 0;
                width_allowed = false;
            }
            _ => return Err(()),
        }
        position += 1;
    }
    if (is_subr && saw_return) || (!is_subr && saw_endchar) {
        Ok(())
    } else {
        Err(())
    }
}

fn cff_nonnegative_usize(value: i64) -> Option<usize> {
    usize::try_from(value).ok()
}

fn cff_clearing_operands(stack_len: usize, exact: usize, width_allowed: bool) -> bool {
    stack_len == exact || (width_allowed && stack_len == exact + 1)
}

fn cff_escaped_char_string_operator_count(operator: u8) -> Option<usize> {
    match operator {
        3 | 4 | 10 | 11 | 12 | 15 | 20 | 24 | 28 | 30 => Some(2),
        5 | 9 | 14 | 18 | 21 | 26 | 27 | 29 => Some(1),
        22 => Some(4),
        23 => Some(0),
        34 => Some(7),
        35 => Some(13),
        36 => Some(9),
        37 => Some(11),
        _ => None,
    }
}

fn cff_dict_operator_count(operator: u8, top_level: bool) -> Option<usize> {
    match operator {
        0 | 6 | 7 | 8 | 9 | 11 => Some(0),
        1 | 2 | 3 | 4 | 10 | 13 | 15 | 16 | 17 | 20 | 21 => Some(1),
        5 => Some(4),
        18 => Some(2),
        19 if !top_level => Some(1),
        _ => None,
    }
}

fn cff_dict_escaped_operator_count(operator: u8) -> Option<usize> {
    match operator {
        0 | 1 | 2 | 3 | 4 | 5 | 6 | 8 | 20 | 21 | 22 | 31 | 32 | 33 | 34 | 35 | 36 | 37 | 38 => {
            Some(1)
        }
        7 => Some(4),
        23 => Some(2),
        30 => Some(3),
        9..=19 | 24..=29 => Some(0),
        _ => None,
    }
}

fn read_cff_number(bytes: &[u8], position: usize, end: usize) -> Result<(i64, usize), ()> {
    if position >= end {
        return Err(());
    }
    let b0 = bytes[position];
    match b0 {
        32..=246 => Ok((i64::from(b0) - 139, position + 1)),
        247..=250 => {
            if position + 1 >= end {
                return Err(());
            }
            Ok((
                i64::from(b0 - 247) * 256 + i64::from(bytes[position + 1]) + 108,
                position + 2,
            ))
        }
        251..=254 => {
            if position + 1 >= end {
                return Err(());
            }
            Ok((
                -i64::from(b0 - 251) * 256 - i64::from(bytes[position + 1]) - 108,
                position + 2,
            ))
        }
        28 => {
            if position + 2 >= end {
                return Err(());
            }
            Ok((
                i64::from(i16::from_be_bytes([
                    bytes[position + 1],
                    bytes[position + 2],
                ])),
                position + 3,
            ))
        }
        30 => Ok((0, parse_cff_real(bytes, position + 1, end)?)),
        255 => {
            if position + 4 >= end {
                return Err(());
            }
            Ok((
                i64::from(i32::from_be_bytes([
                    bytes[position + 1],
                    bytes[position + 2],
                    bytes[position + 3],
                    bytes[position + 4],
                ])),
                position + 5,
            ))
        }
        _ => Err(()),
    }
}

fn parse_cff_real(bytes: &[u8], mut position: usize, end: usize) -> Result<usize, ()> {
    while position < end {
        let byte = bytes[position];
        for nibble in [byte >> 4, byte & 0x0f] {
            if nibble == 0x0f {
                return Ok(position + 1);
            }
            if nibble >= 0x0a && nibble != 0x0e {
                return Err(());
            }
        }
        position += 1;
    }
    Err(())
}

fn read_u16_bounded(bytes: &[u8], position: &mut usize, end: usize) -> Result<u16, ()> {
    let value = be_u16(bytes, *position).ok_or(())?;
    *position = position.checked_add(2).ok_or(())?;
    if *position > end {
        return Err(());
    }
    Ok(value)
}

fn be_i16(bytes: &[u8], offset: usize) -> Option<i16> {
    Some(i16::from_be_bytes([
        *bytes.get(offset)?,
        *bytes.get(offset.checked_add(1)?)?,
    ]))
}

fn be_u16(bytes: &[u8], offset: usize) -> Option<u16> {
    Some(u16::from_be_bytes([
        *bytes.get(offset)?,
        *bytes.get(offset.checked_add(1)?)?,
    ]))
}

fn be_u32(bytes: &[u8], offset: usize) -> Option<u32> {
    Some(u32::from_be_bytes([
        *bytes.get(offset)?,
        *bytes.get(offset.checked_add(1)?)?,
        *bytes.get(offset.checked_add(2)?)?,
        *bytes.get(offset.checked_add(3)?)?,
    ]))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        FetchedResource, PhysicalFetchBudget, RequestRawResourceCache, verify_resource_body,
    };
    use renderweave_renderer_document::validate_render_document;
    use serde_json::{Value, json};

    const FONT_VECTORS: &str = include_str!("../../../font-prepare-cache-vectors-v1.json");
    const ASSET_VECTORS: &str = include_str!(
        "../../../../renderweave-asset/src/test/resources/cn/hbads/renderweave/asset/acceptance-kernel-v1/vectors.json"
    );
    const ALL_KINDS: &str = include_str!("../../../render-document-all-kinds-v1.json");
    const SFNT_CHECKSUM_MAGIC: u32 = 0xB1B0_AFBA;

    #[test]
    fn shared_vector_identity_limits_scope_and_boundary_are_frozen() {
        let vectors: Value = serde_json::from_str(FONT_VECTORS).unwrap();
        assert_eq!(vectors["profile"], "renderweave-font-prepare-cache-v1");
        assert_eq!(
            vectors["rendererProfileIdentity"],
            ResourcePreparationProfile::RendererV1.as_str()
        );
        assert_eq!(
            vectors["assetKernelVectorSha256"],
            format!(
                "sha256:{}",
                hex::encode(Sha256::digest(ASSET_VECTORS.as_bytes()))
            )
        );
        assert_eq!(
            vectors["limits"]["requestUniqueFonts"],
            MAX_REQUEST_UNIQUE_FONTS
        );
        assert_eq!(
            vectors["limits"]["requestUniqueFontsLimitId"],
            REQUEST_UNIQUE_FONTS_LIMIT_ID
        );
        assert_eq!(
            vectors["limits"]["fontTablesPerContent"],
            MAX_FONT_TABLES_PER_CONTENT
        );
        assert_eq!(
            vectors["limits"]["fontTablesPerContentLimitId"],
            FONT_TABLES_PER_CONTENT_LIMIT_ID
        );
        assert_eq!(
            vectors["limits"]["requestFontTables"],
            MAX_REQUEST_FONT_TABLES
        );
        assert_eq!(
            vectors["limits"]["requestFontTablesLimitId"],
            REQUEST_FONT_TABLES_LIMIT_ID
        );
        assert_eq!(vectors["preparedCases"].as_array().unwrap().len(), 2);
        assert_eq!(vectors["failureCases"].as_array().unwrap().len(), 3);
        assert_eq!(vectors["cacheCases"].as_array().unwrap().len(), 4);
        assert_eq!(vectors["budgetCases"].as_array().unwrap().len(), 6);
        assert_eq!(
            vectors["independentReplayScope"]["fontStructureAndFacts"],
            "A2_PYTHON_STDLIB_INDEPENDENT"
        );
        assert_eq!(vectors["boundary"]["fontShaping"], "UNWIRED");
        assert_eq!(
            vectors["boundary"]["nativeFontStack"],
            "BUILD_NOT_AUTHORIZED"
        );
        assert_eq!(vectors["boundary"]["profileAvailability"], "NOT_REGISTERED");
        assert_eq!(vectors["boundary"]["certificationStatus"], "NOT_CERTIFIED");
        assert_eq!(vectors["boundary"]["daemonOutputPath"], "UNWIRED");
        assert_eq!(vectors["boundary"]["productRoute"], "CLOSED");
    }

    #[test]
    fn admitted_ttf_and_otf_are_fully_prepared_with_frozen_facts() {
        let vectors: Value = serde_json::from_str(FONT_VECTORS).unwrap();
        let assets: Value = serde_json::from_str(ASSET_VECTORS).unwrap();
        for case in vectors["preparedCases"].as_array().unwrap() {
            let case_id = case["id"].as_str().unwrap();
            let asset = asset_case(&assets, case["assetCaseId"].as_str().unwrap());
            let bytes = asset_bytes(asset);
            let resource = admitted_resource(
                &bytes,
                case["declaredMediaType"].as_str().unwrap(),
                &asset["expected"]["descriptor"],
                None,
            );
            let raw = prepared_raw(&resource, &bytes);
            let mut cache = RequestPreparedFontCache::new();
            let prepared = cache
                .prepare_or_lookup(
                    &resource,
                    ResourcePreparationProfile::RendererV1,
                    &raw,
                    1_000_000,
                )
                .unwrap_or_else(|problem| panic!("{case_id} failed: {problem:?}"));
            let facts = &case["facts"];
            let flavor = match facts["flavor"].as_str().unwrap() {
                "TRUETYPE_GLYF" => FontFlavor::TrueTypeGlyf,
                "CFF" => FontFlavor::Cff,
                other => panic!("unexpected frozen font flavor {other}"),
            };
            assert!(!prepared.cache_hit());
            assert_eq!(prepared.exact_bytes(), bytes);
            assert!(std::ptr::eq(
                prepared.exact_bytes().as_ptr(),
                raw.bytes().as_ptr()
            ));
            assert_eq!(prepared.flavor(), flavor);
            assert_eq!(
                u64::from(prepared.units_per_em()),
                facts["unitsPerEm"].as_u64().unwrap()
            );
            assert_eq!(
                u64::from(prepared.table_count()),
                facts["tableCount"].as_u64().unwrap()
            );
            assert_eq!(
                u64::from(prepared.glyph_count()),
                facts["glyphCount"].as_u64().unwrap()
            );
            assert_eq!(
                u64::from(prepared.non_empty_outline_count()),
                facts["nonEmptyOutlineCount"].as_u64().unwrap()
            );
            assert_eq!(
                u64::from(prepared.supported_cmap_subtable_count()),
                facts["supportedCmapSubtableCount"].as_u64().unwrap()
            );
            assert!(facts["layoutTables"].as_array().unwrap().is_empty());
            assert!(!prepared.has_gdef());
            assert!(!prepared.has_gsub());
            assert!(!prepared.has_gpos());
            assert!(!prepared.has_kern());
            assert_eq!(cache.unique_content_count(), 1);
            assert_eq!(
                cache.retained_table_count(),
                facts["tableCount"].as_u64().unwrap() as usize
            );
        }
    }

    #[test]
    fn prepared_font_cache_reuses_exact_content_and_rechecks_occurrence_lease() {
        let assets: Value = serde_json::from_str(ASSET_VECTORS).unwrap();
        let asset = asset_case(&assets, "font-ttf-admitted");
        let bytes = asset_bytes(asset);
        let first = admitted_resource(&bytes, "font/ttf", &asset["expected"]["descriptor"], None);
        let second = admitted_resource(
            &bytes,
            "font/ttf",
            &asset["expected"]["descriptor"],
            Some("rwres_cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"),
        );
        let first_raw = prepared_raw(&first, &bytes);
        let second_raw = prepared_raw(&second, &bytes);
        let mut cache = RequestPreparedFontCache::new();
        let inserted = cache
            .prepare_or_lookup(
                &first,
                ResourcePreparationProfile::RendererV1,
                &first_raw,
                1_000_000,
            )
            .unwrap();
        assert!(!inserted.cache_hit());
        let hit = cache
            .prepare_or_lookup(
                &second,
                ResourcePreparationProfile::RendererV1,
                &second_raw,
                1_500_000,
            )
            .unwrap();
        assert!(hit.cache_hit());
        assert_eq!(hit.resource_id(), second.resource_id());
        assert_eq!(cache.unique_content_count(), 1);
        assert_eq!(cache.retained_table_count(), 10);

        let expired = cache
            .prepare_or_lookup(
                &second,
                ResourcePreparationProfile::RendererV1,
                &second_raw,
                2_000_000,
            )
            .unwrap_err();
        assert_eq!(
            expired.code(),
            ResourcePreparationProblemCode::ResourceLeaseExpired
        );
        assert_eq!(cache.unique_content_count(), 1);
    }

    #[test]
    fn deep_ttf_glyph_and_cmap_corruption_fail_after_raw_preflight() {
        let assets: Value = serde_json::from_str(ASSET_VECTORS).unwrap();
        let asset = asset_case(&assets, "font-ttf-admitted");
        for mutation in ["GLYF_CONTOUR", "CMAP_VERSION"] {
            let mut bytes = asset_bytes(asset);
            match mutation {
                "GLYF_CONTOUR" => {
                    let (_, offset, _) = table_record(&bytes, b"glyf");
                    bytes[offset..offset + 2].copy_from_slice(&[0xff, 0xfb]);
                    fix_font_checksums(&mut bytes, b"glyf");
                }
                "CMAP_VERSION" => {
                    let (_, offset, _) = table_record(&bytes, b"cmap");
                    bytes[offset..offset + 2].copy_from_slice(&[0x00, 0x01]);
                    fix_font_checksums(&mut bytes, b"cmap");
                }
                _ => unreachable!(),
            }
            let resource =
                admitted_resource(&bytes, "font/ttf", &asset["expected"]["descriptor"], None);
            let raw = prepared_raw(&resource, &bytes);
            let problem = RequestPreparedFontCache::new()
                .prepare_or_lookup(
                    &resource,
                    ResourcePreparationProfile::RendererV1,
                    &raw,
                    1_000_000,
                )
                .unwrap_err();
            assert_eq!(
                problem.code(),
                ResourcePreparationProblemCode::DecodeFailed,
                "{mutation}"
            );
        }
    }

    #[test]
    fn deep_cff_charstring_corruption_fails_after_raw_preflight() {
        let assets: Value = serde_json::from_str(ASSET_VECTORS).unwrap();
        let invalid = asset_case(&assets, "font-cff-charstrings-invalid");
        let descriptor = asset_case(&assets, "font-otf-admitted");
        let bytes = asset_bytes(invalid);
        let resource = admitted_resource(
            &bytes,
            "font/otf",
            &descriptor["expected"]["descriptor"],
            None,
        );
        let raw = prepared_raw(&resource, &bytes);
        let problem = RequestPreparedFontCache::new()
            .prepare_or_lookup(
                &resource,
                ResourcePreparationProfile::RendererV1,
                &raw,
                1_000_000,
            )
            .unwrap_err();
        assert_eq!(problem.code(), ResourcePreparationProblemCode::DecodeFailed);
    }

    #[test]
    fn font_budget_boundaries_are_inclusive_atomic_and_not_refunded() {
        let assets: Value = serde_json::from_str(ASSET_VECTORS).unwrap();
        let asset = asset_case(&assets, "font-ttf-admitted");
        let bytes = asset_bytes(asset);
        let resource =
            admitted_resource(&bytes, "font/ttf", &asset["expected"]["descriptor"], None);

        let per_font = PreparedFontBudget::default()
            .ensure_can_reserve(&resource, MAX_FONT_TABLES_PER_CONTENT + 1)
            .unwrap_err();
        assert_eq!(per_font.limit_id(), Some(FONT_TABLES_PER_CONTENT_LIMIT_ID));

        let mut inclusive = PreparedFontBudget {
            consumed_unique_fonts: MAX_REQUEST_UNIQUE_FONTS - 1,
            retained_table_count: MAX_REQUEST_FONT_TABLES - MAX_FONT_TABLES_PER_CONTENT,
        };
        inclusive
            .ensure_can_reserve(&resource, MAX_FONT_TABLES_PER_CONTENT)
            .unwrap();
        inclusive.commit(MAX_FONT_TABLES_PER_CONTENT);
        assert_eq!(inclusive.consumed_unique_fonts, MAX_REQUEST_UNIQUE_FONTS);
        assert_eq!(inclusive.retained_table_count, MAX_REQUEST_FONT_TABLES);
        let fonts = inclusive.ensure_can_reserve(&resource, 1).unwrap_err();
        assert_eq!(fonts.limit_id(), Some(REQUEST_UNIQUE_FONTS_LIMIT_ID));
        assert_eq!(inclusive.consumed_unique_fonts, MAX_REQUEST_UNIQUE_FONTS);
        assert_eq!(inclusive.retained_table_count, MAX_REQUEST_FONT_TABLES);

        let tables = PreparedFontBudget {
            consumed_unique_fonts: 0,
            retained_table_count: MAX_REQUEST_FONT_TABLES,
        }
        .ensure_can_reserve(&resource, 1)
        .unwrap_err();
        assert_eq!(tables.limit_id(), Some(REQUEST_FONT_TABLES_LIMIT_ID));
    }

    #[test]
    fn prepared_font_cache_corruption_evicts_without_budget_refund() {
        let assets: Value = serde_json::from_str(ASSET_VECTORS).unwrap();
        let asset = asset_case(&assets, "font-ttf-admitted");
        let bytes = asset_bytes(asset);
        let resource =
            admitted_resource(&bytes, "font/ttf", &asset["expected"]["descriptor"], None);
        let raw = prepared_raw(&resource, &bytes);
        let mut cache = RequestPreparedFontCache::new();
        cache
            .prepare_or_lookup(
                &resource,
                ResourcePreparationProfile::RendererV1,
                &raw,
                1_000_000,
            )
            .unwrap();
        let key = PreparedFontCacheKey::new(&resource, ResourcePreparationProfile::RendererV1);
        let original = cache.entries.get(&key).unwrap();
        let mut corrupted_facts = original.facts;
        corrupted_facts.glyph_count += 1;
        cache.entries.insert(
            key,
            Arc::new(PreparedFontContent {
                source_bytes: Arc::clone(&original.source_bytes),
                source_sha256: original.source_sha256,
                facts: corrupted_facts,
                facts_sha256: original.facts_sha256,
            }),
        );

        let problem = cache
            .prepare_or_lookup(
                &resource,
                ResourcePreparationProfile::RendererV1,
                &raw,
                1_000_000,
            )
            .unwrap_err();
        assert_eq!(
            problem.code(),
            ResourcePreparationProblemCode::RenderInternalError
        );
        assert_eq!(cache.unique_content_count(), 0);
        assert_eq!(cache.budget.consumed_unique_fonts, 1);
        assert_eq!(cache.retained_table_count(), 10);
    }

    #[test]
    fn prepared_font_debug_surfaces_do_not_expose_bytes_or_content_identity() {
        let assets: Value = serde_json::from_str(ASSET_VECTORS).unwrap();
        let asset = asset_case(&assets, "font-ttf-admitted");
        let bytes = asset_bytes(asset);
        let resource =
            admitted_resource(&bytes, "font/ttf", &asset["expected"]["descriptor"], None);
        let raw = prepared_raw(&resource, &bytes);
        let mut cache = RequestPreparedFontCache::new();
        let prepared = cache
            .prepare_or_lookup(
                &resource,
                ResourcePreparationProfile::RendererV1,
                &raw,
                1_000_000,
            )
            .unwrap();
        let debug = format!("{cache:?} {prepared:?}");
        assert!(!debug.contains("sha256:"));
        assert!(!debug.contains("assets.internal"));
        assert!(!debug.contains("AAEAAAA"));
    }

    fn asset_case<'a>(vectors: &'a Value, id: &str) -> &'a Value {
        vectors["cases"]
            .as_array()
            .unwrap()
            .iter()
            .find(|case| case["id"] == id)
            .unwrap_or_else(|| panic!("missing Asset vector {id}"))
    }

    fn asset_bytes(case: &Value) -> Vec<u8> {
        decode_base64(case["input"]["data"].as_str().unwrap())
    }

    fn admitted_resource(
        bytes: &[u8],
        declared_media_type: &str,
        source_descriptor: &Value,
        replacement_resource_id: Option<&str>,
    ) -> AdmittedRenderResource {
        let mut document: Value = serde_json::from_str(ALL_KINDS).unwrap();
        let resource = &mut document["resources"].as_array_mut().unwrap()[0];
        let old_resource_id = resource["resourceId"].as_str().unwrap().to_owned();
        resource["mediaType"] = json!(declared_media_type);
        resource["byteLength"] = json!(bytes.len());
        resource["sha256"] = json!(format!("sha256:{}", hex::encode(Sha256::digest(bytes))));
        resource["technicalDescriptor"] = json!({
            "faceIndex": source_descriptor["faceIndex"],
            "flavor": source_descriptor["flavor"],
            "kind": "font",
            "unitsPerEm": source_descriptor["unitsPerEm"]
        });
        if let Some(replacement) = replacement_resource_id {
            resource["resourceId"] = json!(replacement);
            replace_string(&mut document, &old_resource_id, replacement);
        }
        let canonical = serde_json::to_string(&document).unwrap();
        validate_render_document(&canonical).unwrap().resources()[0].clone()
    }

    fn prepared_raw(resource: &AdmittedRenderResource, bytes: &[u8]) -> PreparedRawResource {
        let mut physical_budget = PhysicalFetchBudget::new();
        let verified = verify_resource_body(resource, &mut physical_budget, [bytes]).unwrap();
        let fetched = FetchedResource::from_verified_parts_for_test(verified, bytes.into());
        RequestRawResourceCache::new()
            .insert_fetched(
                resource,
                ResourcePreparationProfile::RendererV1,
                fetched,
                1_000_000,
            )
            .unwrap()
    }

    fn replace_string(value: &mut Value, old: &str, replacement: &str) {
        match value {
            Value::String(text) if text == old => *text = replacement.to_owned(),
            Value::Array(values) => {
                for value in values {
                    replace_string(value, old, replacement);
                }
            }
            Value::Object(values) => {
                for value in values.values_mut() {
                    replace_string(value, old, replacement);
                }
            }
            _ => {}
        }
    }

    fn table_record(bytes: &[u8], tag: &[u8; 4]) -> (usize, usize, usize) {
        let table_count = usize::from(be_u16(bytes, 4).unwrap());
        for index in 0..table_count {
            let record = 12 + index * 16;
            if &bytes[record..record + 4] == tag {
                return (
                    record,
                    usize::try_from(be_u32(bytes, record + 8).unwrap()).unwrap(),
                    usize::try_from(be_u32(bytes, record + 12).unwrap()).unwrap(),
                );
            }
        }
        panic!("missing font table {}", String::from_utf8_lossy(tag));
    }

    fn fix_font_checksums(bytes: &mut [u8], changed_tag: &[u8; 4]) {
        let (changed_record, changed_offset, changed_length) = table_record(bytes, changed_tag);
        let changed_checksum = sfnt_table_checksum(bytes, changed_offset, changed_length);
        write_u32(bytes, changed_record + 4, changed_checksum);

        let (head_record, head_offset, head_length) = table_record(bytes, b"head");
        write_u32(bytes, head_offset + 8, 0);
        let head_checksum = sfnt_table_checksum(bytes, head_offset, head_length);
        write_u32(bytes, head_record + 4, head_checksum);
        let adjustment =
            SFNT_CHECKSUM_MAGIC.wrapping_sub(sfnt_table_checksum(bytes, 0, bytes.len()));
        write_u32(bytes, head_offset + 8, adjustment);
        assert_eq!(
            sfnt_table_checksum(bytes, 0, bytes.len()),
            SFNT_CHECKSUM_MAGIC
        );
    }

    fn sfnt_table_checksum(bytes: &[u8], offset: usize, length: usize) -> u32 {
        let mut sum = 0_u32;
        for position in (0..length).step_by(4) {
            let mut word = 0_u32;
            for byte_index in 0..4 {
                let index = position + byte_index;
                let byte = if index < length {
                    bytes[offset + index]
                } else {
                    0
                };
                word = (word << 8) | u32::from(byte);
            }
            sum = sum.wrapping_add(word);
        }
        sum
    }

    fn write_u32(bytes: &mut [u8], offset: usize, value: u32) {
        bytes[offset..offset + 4].copy_from_slice(&value.to_be_bytes());
    }

    fn decode_base64(value: &str) -> Vec<u8> {
        let mut output = Vec::with_capacity(value.len() / 4 * 3);
        let bytes = value.as_bytes();
        assert_eq!(bytes.len() % 4, 0);
        for chunk in bytes.chunks_exact(4) {
            let a = base64_value(chunk[0]).unwrap();
            let b = base64_value(chunk[1]).unwrap();
            let c = base64_value(chunk[2]);
            let d = base64_value(chunk[3]);
            output.push((a << 2) | (b >> 4));
            if let Some(c) = c {
                output.push((b << 4) | (c >> 2));
                if let Some(d) = d {
                    output.push((c << 6) | d);
                }
            }
        }
        output
    }

    fn base64_value(byte: u8) -> Option<u8> {
        match byte {
            b'A'..=b'Z' => Some(byte - b'A'),
            b'a'..=b'z' => Some(byte - b'a' + 26),
            b'0'..=b'9' => Some(byte - b'0' + 52),
            b'+' => Some(62),
            b'/' => Some(63),
            b'=' => None,
            _ => panic!("invalid fixture base64"),
        }
    }
}
