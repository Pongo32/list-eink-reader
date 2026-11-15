#include <jni.h>
#include <stdio.h>
#include <string.h>

#include "k2pdfopt.h"

static int convert_bitmap(const char *input_path, const char *output_directory,
                          int source_dpi, int target_width, int target_height,
                          int target_dpi)
{
    K2PDFOPT_SETTINGS settings;
    MASTERINFO master;
    WILLUSBITMAP source;
    WILLUSBITMAP source_grey;
    WILLUSBITMAP output;
    BMPREGION region;
    int status;
    int output_count;
    int initialized_master;
    double output_dpi;
    int size_reduction;

    if (input_path == NULL || output_directory == NULL
            || source_dpi <= 0 || target_width <= 0 || target_height <= 0
            || target_dpi <= 0) {
        return -1;
    }

    bmp_init(&source);
    bmp_init(&source_grey);
    bmp_init(&output);
    bmpregion_init(&region);
    initialized_master = 0;
    output_count = 0;

    status = bmp_read(&source, (char *)input_path, NULL);
    if (status < 0) {
        goto cleanup;
    }

    k2pdfopt_settings_init(&settings);
    settings.use_crop_boxes = 0;
    settings.src_rot = 0;
    settings.erase_vertical_lines = 0;
    settings.src_autostraighten = 0;
    settings.user_src_dpi = source_dpi;
    settings.src_dpi = source_dpi;
    settings.dst_dpi = target_dpi;
    settings.dst_userwidth = (double)target_width / target_dpi;
    settings.dst_userheight = (double)target_height / target_dpi;
    settings.dst_userwidth_units = UNITS_INCHES;
    settings.dst_userheight_units = UNITS_INCHES;
    settings.display_width_inches = settings.dst_userwidth;
    settings.max_region_width_inches = settings.dst_userwidth;
    k2pdfopt_settings_sanity_check(&settings);
    k2pdfopt_settings_new_source_document_init(&settings);

    masterinfo_init(&master, &settings);
    initialized_master = 1;
    status = masterinfo_new_source_page_init(&master, &settings, &source,
            &source_grey, NULL, &region, 0.0, NULL, NULL, 1, NULL);
    if (status < 0) {
        goto cleanup;
    }

    bmpregion_source_page_add(&region, &settings, &master, 1, 0);
    bmp_free(&source_grey);
    bmp_free(&source);

    while (masterinfo_get_next_output_page(&master, &settings, 1, &output,
            &output_dpi, &size_reduction, NULL) > 0) {
        char output_path[1024];
        int written;
        output_count++;
        written = snprintf(output_path, sizeof(output_path),
                "%s/page_%03d.bmp", output_directory, output_count);
        if (written < 0 || written >= (int)sizeof(output_path)
                || bmp_write(&output, output_path, NULL, 0) < 0) {
            output_count = -1;
            goto cleanup;
        }
    }
    if (output_count == 0) {
        output_count = -1;
    }

cleanup:
    bmp_free(&output);
    if (initialized_master) {
        masterinfo_free(&master, &settings);
    }
    bmpregion_free(&region);
    bmp_free(&source_grey);
    bmp_free(&source);
    return output_count < 0 ? output_count : (status < 0 ? status : output_count);
}

JNIEXPORT jint JNICALL
Java_ru_reader_eink_K2PdfOpt_convertBitmap(JNIEnv *env, jclass type,
        jstring input_path, jstring output_directory, jint source_dpi,
        jint target_width, jint target_height, jint target_dpi)
{
    const char *input = NULL;
    const char *output = NULL;
    int result;
    (void)type;

    if (input_path == NULL || output_directory == NULL) {
        return -1;
    }
    input = (*env)->GetStringUTFChars(env, input_path, NULL);
    if (input == NULL) {
        return -1;
    }
    output = (*env)->GetStringUTFChars(env, output_directory, NULL);
    if (output == NULL) {
        (*env)->ReleaseStringUTFChars(env, input_path, input);
        return -1;
    }

    result = convert_bitmap(input, output, source_dpi, target_width,
            target_height, target_dpi);

    (*env)->ReleaseStringUTFChars(env, output_directory, output);
    (*env)->ReleaseStringUTFChars(env, input_path, input);
    return result;
}
