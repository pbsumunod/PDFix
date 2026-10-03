-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn java.awt.**
-dontwarn javax.imageio.**
# PDFBox's documented optional JPEG2000 codec is not included. This app renders
# through Android PdfRenderer; it never invokes PDFBox's optional image renderer.
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
