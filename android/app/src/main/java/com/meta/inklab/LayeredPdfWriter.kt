package com.meta.inklab

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.DeflaterOutputStream

data class PdfRasterLayer(
    val name: String,
    val width: Int,
    val height: Int,
    val rgb: ByteArray,
    val alpha: ByteArray? = null,
)

data class PdfOutputPageSize(
    val widthPoints: Float,
    val heightPoints: Float,
)

object LayeredPdfWriter {
    fun write(
        output: OutputStream,
        layers: List<PdfRasterLayer>,
        pageSize: PdfOutputPageSize? = null,
    ) {
        require(layers.isNotEmpty())
        val width = layers.first().width
        val height = layers.first().height
        require(width > 0 && height > 0)
        require(layers.all { it.width == width && it.height == height && it.rgb.size == width * height * 3 })
        val pageWidth = pageSize?.widthPoints?.takeIf { it > 0f } ?: width.toFloat()
        val pageHeight = pageSize?.heightPoints?.takeIf { it > 0f } ?: height.toFloat()

        val objects = mutableListOf<ByteArray>()
        fun reserve(): Int = objects.run { add(ByteArray(0)); size }
        fun set(id: Int, bytes: ByteArray) { objects[id - 1] = bytes }
        fun ascii(value: String) = value.toByteArray(StandardCharsets.ISO_8859_1)

        val catalogId = reserve()
        val pagesId = reserve()
        val pageId = reserve()
        val contentId = reserve()
        val layerRecords =
            layers.mapIndexed { index, layer ->
                val ocgId = reserve()
                val maskId = layer.alpha?.takeUnless { alpha -> alpha.all { it == 0xFF.toByte() } }?.let { reserve() }
                val imageId = reserve()
                LayerRecord(index + 1, layer, ocgId, maskId, imageId)
            }

        layerRecords.forEach { record ->
            set(record.ocgId, ascii("<< /Type /OCG /Name ${pdfString(record.layer.name)} >>"))
            record.maskId?.let { maskId ->
                set(maskId, imageStream(record.layer.width, record.layer.height, "/DeviceGray", deflate(record.layer.alpha!!), null))
            }
            set(
                record.imageId,
                imageStream(record.layer.width, record.layer.height, "/DeviceRGB", deflate(record.layer.rgb), record.maskId),
            )
        }

        val content =
            buildString {
                layerRecords.forEach { record ->
                    append("/OC /L${record.number} BDC\n")
                    append("q ${pdfNumber(pageWidth)} 0 0 ${pdfNumber(pageHeight)} 0 0 cm /Im${record.number} Do Q\n")
                    append("EMC\n")
                }
            }.toByteArray(StandardCharsets.US_ASCII)
        set(contentId, streamObject("", content))

        val properties = layerRecords.joinToString(" ") { "/L${it.number} ${it.ocgId} 0 R" }
        val images = layerRecords.joinToString(" ") { "/Im${it.number} ${it.imageId} 0 R" }
        set(
            pageId,
            ascii(
                "<< /Type /Page /Parent $pagesId 0 R /MediaBox [0 0 ${pdfNumber(pageWidth)} ${pdfNumber(pageHeight)}] " +
                    "/Resources << /Properties << $properties >> /XObject << $images >> >> /Contents $contentId 0 R >>",
            ),
        )
        set(pagesId, ascii("<< /Type /Pages /Kids [$pageId 0 R] /Count 1 >>"))
        val ocgs = layerRecords.joinToString(" ") { "${it.ocgId} 0 R" }
        set(
            catalogId,
            ascii(
                "<< /Type /Catalog /Pages $pagesId 0 R /OCProperties << /OCGs [$ocgs] " +
                    "/D << /Name (Mojian layers) /Order [$ocgs] /ON [$ocgs] >> >> >>",
            ),
        )

        val buffer = ByteArrayOutputStream()
        buffer.write(ascii("%PDF-1.7\n%\u00e2\u00e3\u00cf\u00d3\n"))
        val offsets = IntArray(objects.size + 1)
        objects.forEachIndexed { index, bytes ->
            val id = index + 1
            offsets[id] = buffer.size()
            buffer.write(ascii("$id 0 obj\n"))
            buffer.write(bytes)
            buffer.write(ascii("\nendobj\n"))
        }
        val xrefOffset = buffer.size()
        buffer.write(ascii("xref\n0 ${objects.size + 1}\n"))
        buffer.write(ascii("0000000000 65535 f \n"))
        for (id in 1..objects.size) buffer.write(ascii(String.format("%010d 00000 n \n", offsets[id])))
        buffer.write(ascii("trailer\n<< /Size ${objects.size + 1} /Root $catalogId 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n"))
        buffer.writeTo(output)
    }

    private fun imageStream(width: Int, height: Int, colorSpace: String, compressed: ByteArray, maskId: Int?): ByteArray {
        val extra = maskId?.let { " /SMask $it 0 R" }.orEmpty()
        return streamObject(
            "/Type /XObject /Subtype /Image /Width $width /Height $height /ColorSpace $colorSpace /BitsPerComponent 8 /Filter /FlateDecode$extra",
            compressed,
        )
    }

    private fun streamObject(dictionary: String, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write("<< $dictionary /Length ${payload.size} >>\nstream\n".toByteArray(StandardCharsets.US_ASCII))
            write(payload)
            write("\nendstream".toByteArray(StandardCharsets.US_ASCII))
        }.toByteArray()

    private fun deflate(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { target -> DeflaterOutputStream(target).use { it.write(bytes) } }.toByteArray()

    private fun pdfString(value: String): String =
        buildString {
            append("<FEFF")
            value.toByteArray(Charsets.UTF_16BE).forEach { byte -> append(String.format("%02X", byte.toInt() and 0xFF)) }
            append('>')
        }

    private fun pdfNumber(value: Float): String {
        val rounded = value.toInt()
        return if (value == rounded.toFloat()) rounded.toString() else String.format(java.util.Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
    }

    private data class LayerRecord(
        val number: Int,
        val layer: PdfRasterLayer,
        val ocgId: Int,
        val maskId: Int?,
        val imageId: Int,
    )
}
