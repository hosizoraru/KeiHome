package os.kei.hyper.home.xposed

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Node

/** Approval is only for the static output of our APK/classic-ZIP converter, never opaque MTZs. */
internal object ConvertedIconArchive {
  const val MAX_BYTES = 256L * 1024 * 1024
  private val origins = setOf("CONVERTED_APK", "CONVERTED_CLASSIC_ZIP")
  private val iconName = Regex("(?:nightmode/)?res/drawable-xxhdpi/[\\p{L}\\p{N}_$]+(?:\\.[\\p{L}\\p{N}_$]+)+\\.png")
  data class Digests(val sha1: String, val sha256: String)
  data class Proof(val origin: String, val archive: Digests, val icons: Digests)

  fun inspect(file: File, origin: String, workDirectory: File = requireNotNull(file.parentFile)): Proof {
    require(origin in origins) { "Unapproved conversion origin" }
    require(file.length() in 1..MAX_BYTES) { "Converted archive size limit" }
    val archive = digest(file)
    var icons: Digests? = null
    ZipFile(file).use { zip ->
      require(zip.size() == 3) { "Expected converted description, icons and preview only" }
      val seen = HashSet<String>()
      zip.entries().asSequence().forEach { entry ->
        checkInterrupted()
        require(!entry.isDirectory && seen.add(entry.name)) { "Duplicate/directory converted entry" }
        val limit = when (entry.name) {
          "icons" -> MAX_BYTES
          "description.xml" -> 64L * 1024
          "preview/preview_icons_0.png" -> 8L * 1024 * 1024
          else -> error("Unexpected converted archive entry: ${entry.name}")
        }
        require(entry.size in 1..limit) { "Converted entry size limit" }
        val crc = CRC32()
        val hashes = zip.getInputStream(entry).use { digest(it, entry.size, crc) }
        require(crc.value == entry.crc) { "Converted entry CRC mismatch" }
        when (entry.name) {
          "icons" -> {
            // Validate the same central-directory view used by native ZIP readers. A streaming
            // local-header-only parser can miss a differently named central-directory entry.
            val nested = File.createTempFile("keihome-converted-", ".icons", workDirectory)
            try {
              zip.getInputStream(entry).use { input -> nested.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                  checkInterrupted()
                  val count = input.read(buffer)
                  if (count < 0) break
                  copied += count
                  require(copied <= entry.size)
                  output.write(buffer, 0, count)
                }
                require(copied == entry.size)
              } }
              require(digest(nested) == hashes)
              validateIcons(nested)
            } finally { check(nested.delete()) { "Could not remove converted validation copy" } }
            icons = hashes
          }
          "description.xml" -> zip.getInputStream(entry).use { validateDescription(readLimited(it, limit)) }
          else -> zip.getInputStream(entry).use { validatePng(readLimited(it, limit), 1024) }
        }
      }
      require(seen == setOf("description.xml", "icons", "preview/preview_icons_0.png"))
    }
    require(digest(file) == archive) { "Converted archive changed during inspection" }
    return Proof(origin, archive, requireNotNull(icons))
  }

  fun digest(file: File): Digests {
    require(file.isFile && file.length() in 1..MAX_BYTES) { "Expected bounded content file" }
    return file.inputStream().use { digest(it, MAX_BYTES) }
  }

  private fun digest(input: InputStream, limit: Long, crc: CRC32? = null): Digests {
    val sha1 = MessageDigest.getInstance("SHA-1") // Native identity; SHA-256 binds the approval.
    val sha256 = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var count = 0L
    while (true) {
      checkInterrupted()
      val size = input.read(buffer)
      if (size < 0) break
      count += size
      require(count <= limit) { "Converted expanded size limit" }
      sha1.update(buffer, 0, size); sha256.update(buffer, 0, size); crc?.update(buffer, 0, size)
    }
    if (crc != null) require(count == limit) { "Converted entry length mismatch" }
    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    return Digests(hex(sha1.digest()), hex(sha256.digest()))
  }

  private fun validateIcons(file: File) {
    val names = HashSet<String>()
    var total = 0L
    ZipFile(file).use { zip ->
      require(zip.size() in 2..20000) { "Converted icon count limit" }
      zip.entries().asSequence().forEach { entry ->
        checkInterrupted()
        require(!entry.isDirectory && iconName.matches(entry.name) && entry.name.length <= 300 &&
          names.add(entry.name) && names.size <= 20000) { "Not a static converted PNG icon: ${entry.name}" }
        val limit = minOf(8L * 1024 * 1024, MAX_BYTES - total)
        require(entry.size in 1..limit) { "Converted PNG size limit" }
        val png = zip.getInputStream(entry).use { readLimited(it, limit) }
        require(png.size.toLong() == entry.size && CRC32().apply { update(png) }.value == entry.crc) {
          "Converted PNG ZIP integrity mismatch"
        }
        total += png.size
        validatePng(png, 512)
      }
    }
    require(names.any { !it.startsWith("nightmode/") } && names.any { it.startsWith("nightmode/") }) {
      "Converted output must contain day and native nightmode PNG artwork"
    }
  }

  private fun readLimited(input: InputStream, limit: Long): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
      checkInterrupted()
      val size = input.read(buffer)
      if (size < 0) break
      require(output.size().toLong() + size <= limit) { "Converted entry size limit" }
      output.write(buffer, 0, size)
    }
    return output.toByteArray()
  }

  private fun validatePng(bytes: ByteArray, maxDimension: Int) {
    val input = DataInputStream(ByteArrayInputStream(bytes))
    require(input.readLong() == -8552249625308161526L) { "Converted artwork is not PNG" }
    var header = false
    var pixels = false
    val allowed = setOf("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "sRGB", "gAMA", "cHRM", "iCCP", "sBIT", "pHYs")
    while (input.available() > 0) {
      val length = input.readInt()
      require(length >= 0 && length <= input.available() - 8) { "Invalid PNG chunk size" }
      val type = ByteArray(4).also(input::readFully)
      val name = String(type, Charsets.US_ASCII)
      require(name in allowed && (header || name == "IHDR")) { "Unexpected PNG chunk" }
      val data = ByteArray(length).also(input::readFully)
      val crc = CRC32().apply { update(type); update(data) }
      require(crc.value == (input.readInt().toLong() and 0xffffffffL)) { "PNG CRC mismatch" }
      when (name) {
        "IHDR" -> {
          require(!header && length == 13)
          val dims = DataInputStream(ByteArrayInputStream(data))
          require(dims.readInt() in 1..maxDimension && dims.readInt() in 1..maxDimension) { "PNG dimension limit" }
          header = true
        }
        "IDAT" -> pixels = true
        "IEND" -> {
          require(length == 0 && header && pixels && input.available() == 0) { "Invalid PNG end" }
          return
        }
      }
    }
    error("Missing PNG end")
  }

  private fun validateDescription(bytes: ByteArray) {
    val xml = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
    require('\u0000' !in xml && '\uFFFD' !in xml) { "Converted metadata must be UTF-8" }
    val body = xml.replaceFirst(Regex("^\\s*<\\?xml[^?]*\\?>"), "")
    require(!body.contains("<!") && !body.contains("<?")) { "Converted metadata cannot contain declarations" }
    val builder = DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = false
      isExpandEntityReferences = false
    }.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> error("External metadata entity") }
    val root = builder.parse(ByteArrayInputStream(bytes)).documentElement
    require(root.nodeName == "MIUI-Theme" && root.attributes.length == 0)
    val allowed = setOf("title", "author", "designer", "version", "uiVersion", "description")
    val seen = HashSet<String>()
    for (i in 0 until root.childNodes.length) {
      val node = root.childNodes.item(i)
      if (node.nodeType == Node.TEXT_NODE && node.nodeValue.isBlank()) continue
      require(node.nodeType == Node.ELEMENT_NODE && node.nodeName in allowed &&
        seen.add(node.nodeName) && node.attributes.length == 0) { "Unexpected converted metadata" }
      for (j in 0 until node.childNodes.length) require(node.childNodes.item(j).nodeType == Node.TEXT_NODE)
    }
    require(seen == allowed) { "Incomplete converted metadata" }
  }

  private fun checkInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("Converted icon inspection interrupted")
  }
}

/** Stored IDs are associations, never a title-only, online-ID, or hash-only global permission. */
internal data class ConvertedIconGrant(
  val proof: ConvertedIconArchive.Proof,
  val localId: String,
  val parentLocalId: String,
) {
  fun matches(icon: ConvertedIconResource, parent: ConvertedIconResource): Boolean =
    icon.localId == localId && icon.hash == proof.icons.sha1 && icon.hasNoStoreId &&
      parent.localId == parentLocalId && parent.hash == proof.archive.sha1 && parent.hasNoStoreId
}

internal data class ConvertedIconResource(val localId: String, val hash: String,
  val onlineId: String?, val productId: String?, val assemblyId: String?) {
  val hasNoStoreId: Boolean get() = onlineId.isNullOrEmpty() && productId.isNullOrEmpty() && assemblyId.isNullOrEmpty()
}
