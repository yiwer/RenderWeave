use renderweave_renderer_resource::{
    FONT_TABLES_PER_CONTENT_LIMIT_ID, MAX_FONT_TABLES_PER_CONTENT, MAX_REQUEST_FONT_TABLES,
    MAX_REQUEST_UNIQUE_FONTS, REQUEST_FONT_TABLES_LIMIT_ID, REQUEST_UNIQUE_FONTS_LIMIT_ID,
    RequestPreparedFontCache,
};

#[test]
fn prepared_font_cache_limits_are_public_and_exact() {
    assert_eq!(MAX_REQUEST_UNIQUE_FONTS, 32);
    assert_eq!(
        REQUEST_UNIQUE_FONTS_LIMIT_ID,
        "layoutFontAndRaster.uniqueFonts"
    );
    assert_eq!(MAX_FONT_TABLES_PER_CONTENT, 256);
    assert_eq!(
        FONT_TABLES_PER_CONTENT_LIMIT_ID,
        "layoutFontAndRaster.tablesPerFont"
    );
    assert_eq!(MAX_REQUEST_FONT_TABLES, 4_096);
    assert_eq!(
        REQUEST_FONT_TABLES_LIMIT_ID,
        "layoutFontAndRaster.fontTablesTotal"
    );

    let cache = RequestPreparedFontCache::new();
    assert_eq!(cache.unique_content_count(), 0);
    assert_eq!(cache.retained_table_count(), 0);
}
