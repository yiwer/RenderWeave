#include <setjmp.h>
#include <limits.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <jpeglib.h>

typedef int (*renderweave_checkpoint_fn)(void *context);

struct renderweave_jpeg_error {
  struct jpeg_error_mgr base;
  jmp_buf jump;
};

struct renderweave_huffman_tables {
  uint8_t dc_luma_bits[17];
  uint8_t dc_luma_values[12];
  uint8_t ac_luma_bits[17];
  uint8_t ac_luma_values[162];
  uint8_t dc_chroma_bits[17];
  uint8_t dc_chroma_values[12];
  uint8_t ac_chroma_bits[17];
  uint8_t ac_chroma_values[162];
};

struct renderweave_jpeg_state {
  struct jpeg_compress_struct compressor;
  struct renderweave_jpeg_error error;
  unsigned char *encoded;
  unsigned long encoded_length;
};

static void renderweave_error_exit(j_common_ptr common) {
  struct renderweave_jpeg_error *error =
      (struct renderweave_jpeg_error *)common->err;
  longjmp(error->jump, 1);
}

static void install_huffman_table(j_compress_ptr compressor,
                                  JHUFF_TBL **target,
                                  const UINT8 bits[17],
                                  const UINT8 *values,
                                  size_t value_count) {
  if (*target == NULL) {
    *target = jpeg_alloc_huff_table((j_common_ptr)compressor);
  }
  memcpy((*target)->bits, bits, 17);
  memset((*target)->huffval, 0, 256);
  memcpy((*target)->huffval, values, value_count);
  (*target)->sent_table = FALSE;
}

static void install_huffman_tables(
    j_compress_ptr compressor,
    const struct renderweave_huffman_tables *tables) {
  install_huffman_table(compressor, &compressor->dc_huff_tbl_ptrs[0],
                        tables->dc_luma_bits, tables->dc_luma_values,
                        sizeof(tables->dc_luma_values));
  install_huffman_table(compressor, &compressor->ac_huff_tbl_ptrs[0],
                        tables->ac_luma_bits, tables->ac_luma_values,
                        sizeof(tables->ac_luma_values));
  install_huffman_table(compressor, &compressor->dc_huff_tbl_ptrs[1],
                        tables->dc_chroma_bits, tables->dc_chroma_values,
                        sizeof(tables->dc_chroma_values));
  install_huffman_table(compressor, &compressor->ac_huff_tbl_ptrs[1],
                        tables->ac_chroma_bits, tables->ac_chroma_values,
                        sizeof(tables->ac_chroma_values));
}

static int destroy_failed_state(struct renderweave_jpeg_state *state,
                                int status) {
  jpeg_destroy_compress(&state->compressor);
  free(state->encoded);
  free(state);
  return status;
}

int renderweave_encode_jpeg(const uint8_t *rgb, uint32_t width,
                            uint32_t height, uint16_t dpi,
                            const uint8_t luma_table[64],
                            const uint8_t chroma_table[64],
                            const struct renderweave_huffman_tables *huffman,
                            const uint8_t *icc, size_t icc_length,
                            size_t encoder_scratch_bytes,
                            renderweave_checkpoint_fn checkpoint,
                            void *checkpoint_context, uint8_t **output,
                            size_t *output_length) {
  unsigned int luma[64];
  unsigned int chroma[64];
  struct renderweave_jpeg_state *state;
  j_compress_ptr compressor;

  if (rgb == NULL || output == NULL || output_length == NULL || width == 0 ||
      height == 0 || dpi == 0 || luma_table == NULL || chroma_table == NULL ||
      huffman == NULL || icc == NULL || icc_length == 0 ||
      icc_length > 65519 || encoder_scratch_bytes == 0 ||
      encoder_scratch_bytes > (size_t)LONG_MAX || checkpoint == NULL) {
    return 1;
  }
  *output = NULL;
  *output_length = 0;
  state = (struct renderweave_jpeg_state *)calloc(1, sizeof(*state));
  if (state == NULL) {
    return 2;
  }
  compressor = &state->compressor;
  compressor->err = jpeg_std_error(&state->error.base);
  state->error.base.error_exit = renderweave_error_exit;
  if (setjmp(state->error.jump) != 0) {
    return destroy_failed_state(state, 2);
  }

  jpeg_create_compress(compressor);
  /* Override JPEGMEM and the allocator's ambient chunk default.  This is the
   * pinned encoder-scratch reservation for the exact output profile. */
  compressor->mem->max_memory_to_use = (long)encoder_scratch_bytes;
  compressor->mem->max_alloc_chunk = (long)encoder_scratch_bytes;
  jpeg_mem_dest(compressor, &state->encoded, &state->encoded_length);
  compressor->image_width = width;
  compressor->image_height = height;
  compressor->input_components = 3;
  compressor->in_color_space = JCS_RGB;
  compressor->input_gamma = 1.0;

  /* These calls allocate libjpeg's required structures. They are not the
   * output contract: every observable v1 field and every table is overwritten
   * below, and Rust rejects any marker/table drift before returning bytes. */
  jpeg_set_defaults(compressor);
  jpeg_set_colorspace(compressor, JCS_YCbCr);

  compressor->data_precision = 8;
  compressor->num_components = 3;
  compressor->jpeg_color_space = JCS_YCbCr;
  compressor->raw_data_in = FALSE;
  compressor->arith_code = FALSE;
  compressor->optimize_coding = FALSE;
  compressor->CCIR601_sampling = FALSE;
  compressor->smoothing_factor = 0;
  compressor->dct_method = JDCT_ISLOW;
  compressor->restart_interval = 0;
  compressor->restart_in_rows = 0;
  compressor->progressive_mode = FALSE;
  compressor->num_scans = 0;
  compressor->scan_info = NULL;
  compressor->write_JFIF_header = TRUE;
  compressor->JFIF_major_version = 1;
  compressor->JFIF_minor_version = 2;
  compressor->density_unit = 1;
  compressor->X_density = dpi;
  compressor->Y_density = dpi;
  compressor->write_Adobe_marker = FALSE;

  for (int index = 0; index < 3; ++index) {
    compressor->comp_info[index].h_samp_factor = 1;
    compressor->comp_info[index].v_samp_factor = 1;
    compressor->comp_info[index].dc_tbl_no = index == 0 ? 0 : 1;
    compressor->comp_info[index].ac_tbl_no = index == 0 ? 0 : 1;
    compressor->comp_info[index].quant_tbl_no = index == 0 ? 0 : 1;
  }
  for (int index = 0; index < 64; ++index) {
    luma[index] = luma_table[index];
    chroma[index] = chroma_table[index];
  }
  jpeg_add_quant_table(compressor, 0, luma, 100, TRUE);
  jpeg_add_quant_table(compressor, 1, chroma, 100, TRUE);
  install_huffman_tables(compressor, huffman);

  if (checkpoint(checkpoint_context) != 0) {
    return destroy_failed_state(state, 3);
  }
  jpeg_start_compress(compressor, TRUE);
  {
    size_t marker_length = 14 + icc_length;
    JOCTET *marker = (JOCTET *)malloc(marker_length);
    if (marker == NULL) {
      return destroy_failed_state(state, 2);
    }
    memcpy(marker, "ICC_PROFILE\0", 12);
    marker[12] = 1;
    marker[13] = 1;
    memcpy(marker + 14, icc, icc_length);
    jpeg_write_marker(compressor, JPEG_APP0 + 2, marker,
                      (unsigned int)marker_length);
    free(marker);
  }
  while (compressor->next_scanline < compressor->image_height) {
    JSAMPROW row;
    if (checkpoint(checkpoint_context) != 0) {
      return destroy_failed_state(state, 3);
    }
    row = (JSAMPROW)(rgb + (size_t)compressor->next_scanline *
                              (size_t)width * 3);
    if (jpeg_write_scanlines(compressor, &row, 1) != 1) {
      return destroy_failed_state(state, 2);
    }
  }
  jpeg_finish_compress(compressor);
  jpeg_destroy_compress(compressor);
  if (checkpoint(checkpoint_context) != 0) {
    free(state->encoded);
    free(state);
    return 3;
  }
  if (state->encoded == NULL || state->encoded_length == 0 ||
      state->encoded_length > SIZE_MAX) {
    free(state->encoded);
    free(state);
    return 2;
  }
  *output = state->encoded;
  *output_length = (size_t)state->encoded_length;
  state->encoded = NULL;
  free(state);
  return 0;
}

void renderweave_free_jpeg(uint8_t *output) { free(output); }
