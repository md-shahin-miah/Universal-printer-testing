package com.valt.printertestapplication.printer

object LabelPrinterCommands {

    enum class LabelLanguage {
        ZPL,    // Zebra Programming Language (Zebra printers)
        TSPL,   // TSC Printer Language (TSC printers)
        EPL,    // Eltron Programming Language (older Zebra/Eltron)
        CPCL,   // Comtec Printer Control Language (mobile printers)
        ESC_POS // Standard ESC/POS (some label printers support this)
    }

    // ==================== PRINTER STATUS COMMANDS ====================

    // TSC/TSPL Status Query Commands
    object TsplStatus {
        // Method 1: ESC ! ? - Real-time status query (most common)
        fun queryStatus(): ByteArray = byteArrayOf(0x1B, 0x21, 0x3F)  // ESC ! ?
        
        // Method 2: ESC ! S - Status query
        fun queryStatusAlt(): ByteArray = byteArrayOf(0x1B, 0x21, 0x53)  // ESC ! S
        
        // Method 3: <ESC>!R - Request status
        fun queryStatusR(): ByteArray = byteArrayOf(0x1B, 0x21, 0x52)  // ESC ! R
        
        // Method 4: DLE EOT (like ESC/POS) - some TSC support this
        fun queryStatusDLE(): ByteArray = byteArrayOf(0x10, 0x04, 0x01)  // DLE EOT 1
        
        // Method 5: ~!S - TSPL status command
        fun queryStatusTspl(): ByteArray = "~!S\r\n".toByteArray(Charsets.US_ASCII)
        
        // Method 6: ~!C - Check status
        fun queryStatusC(): ByteArray = "~!C\r\n".toByteArray(Charsets.US_ASCII)

        // Query printer model/info
        fun queryInfo(): ByteArray = "~!I\r\n".toByteArray(Charsets.US_ASCII)

        // Query firmware version
        fun queryVersion(): ByteArray = "~!F\r\n".toByteArray(Charsets.US_ASCII)

        // Query mileage (print distance)
        fun queryMileage(): ByteArray = "~!@\r\n".toByteArray(Charsets.US_ASCII)

        // Self-test print
        fun selfTest(): ByteArray = "~!T\r\n".toByteArray(Charsets.US_ASCII)
        
        // Method 7: STATUS command (some TSC models)
        fun queryStatusCmd(): ByteArray = "STATUS\r\n".toByteArray(Charsets.US_ASCII)
        
        // Method 8: ?S query
        fun queryStatusQS(): ByteArray = "?S\r\n".toByteArray(Charsets.US_ASCII)
        
        // Method 9: GS a n - Enable/disable Automatic Status Back (ASB)
        fun queryStatusGS(): ByteArray = byteArrayOf(0x1D, 0x61, 0x01)  // GS a 1
        
        // Method 10: GS r n - Transmit status
        fun queryStatusGSr(): ByteArray = byteArrayOf(0x1D, 0x72, 0x01)  // GS r 1
        
        // Get all status commands to try
        fun getAllStatusCommands(): List<Pair<String, ByteArray>> = listOf(
            "ESC!?" to queryStatus(),
            "ESC!S" to queryStatusAlt(),
            "DLE_EOT" to queryStatusDLE(),
            "GS_r_1" to queryStatusGSr(),
            "STATUS" to queryStatusCmd(),
            "~!S" to queryStatusTspl(),
            "~!C" to queryStatusC(),
            "?S" to queryStatusQS(),
            "ESC!R" to queryStatusR(),
            "GS_a_1" to queryStatusGS()
        )

        // Parse TSC status byte response
        fun parseStatus(statusByte: Byte): PrinterStatus {
            val status = statusByte.toInt() and 0xFF
            return PrinterStatus(
                isReady = status == 0x00,
                isPaperOut = (status and 0x04) != 0,  // Bit 2: Paper out
                isRibbonOut = (status and 0x08) != 0, // Bit 3: Ribbon out
                isPaused = (status and 0x10) != 0,    // Bit 4: Paused
                isPrinting = (status and 0x20) != 0,  // Bit 5: Printing
                isCoverOpen = (status and 0x40) != 0, // Bit 6: Cover open
                hasError = (status and 0x80) != 0,    // Bit 7: Other error
                rawStatus = status
            )
        }
    }

    // ZPL (Zebra) Status Query Commands
    object ZplStatus {
        // Host Status Return
        fun queryStatus(): ByteArray = "~HS".toByteArray(Charsets.UTF_8)

        // Host Identification
        fun queryInfo(): ByteArray = "~HI".toByteArray(Charsets.UTF_8)

        // Host RAM Status
        fun queryMemory(): ByteArray = "~HM".toByteArray(Charsets.UTF_8)

        // Print Configuration Label
        fun printConfig(): ByteArray = "~WC".toByteArray(Charsets.UTF_8)
    }

    // EPL Status Query Commands
    object EplStatus {
        // Query status
        fun queryStatus(): ByteArray = "\r\nS\r\n".toByteArray(Charsets.US_ASCII)

        // Query configuration
        fun queryConfig(): ByteArray = "\r\nU\r\n".toByteArray(Charsets.US_ASCII)
    }

    // ESC/POS Status Query Commands
    object EscPosStatus {
        // DLE EOT - Transmit real-time status
        fun queryStatus(): ByteArray = byteArrayOf(0x10, 0x04, 0x01) // Paper sensor status

        fun queryPaperStatus(): ByteArray = byteArrayOf(0x10, 0x04, 0x04) // Paper roll sensor

        // GS a - Enable Automatic Status Back (ASB)
        fun enableAutoStatus(): ByteArray = byteArrayOf(0x1D, 0x61, 0x0F)

        // Parse ESC/POS status
        fun parsePaperStatus(statusByte: Byte): Boolean {
            // Bit 5-6: Paper near end / Paper not present
            return (statusByte.toInt() and 0x60) != 0
        }
    }

    // Printer Status data class
    data class PrinterStatus(
        val isReady: Boolean = false,
        val isPaperOut: Boolean = false,
        val isRibbonOut: Boolean = false,
        val isPaused: Boolean = false,
        val isPrinting: Boolean = false,
        val isCoverOpen: Boolean = false,
        val hasError: Boolean = false,
        val rawStatus: Int = 0,
        val statusMessage: String = ""
    ) {
        fun getStatusText(): String {
            val issues = mutableListOf<String>()
            if (isPaperOut) issues.add("Paper Out")
            if (isRibbonOut) issues.add("Ribbon Out")
            if (isPaused) issues.add("Paused")
            if (isCoverOpen) issues.add("Cover Open")
            if (hasError) issues.add("Error")
            if (isPrinting) issues.add("Printing")

            return if (issues.isEmpty()) "Ready" else issues.joinToString(", ")
        }
    }

    // ==================== ZPL Commands (Zebra) ====================

    
    fun buildZplTestLabel(): ByteArray {
        val zpl = StringBuilder()
        zpl.append("^XA")                    // Start format
        zpl.append("^LH0,0")                 // Label home (0,0) - no margin
        zpl.append("^CF0,40")                // Font size
        zpl.append("^FO0,0")                 // Field origin x=0 (left edge)
        zpl.append("^FD")                    // Field data start
        zpl.append("PRINTER TEST")
        zpl.append("^FS")                    // Field separator
        zpl.append("^FO0,50")
        zpl.append("^FD")
        zpl.append("Label Print OK")
        zpl.append("^FS")
        zpl.append("^FO0,100")
        zpl.append("^FD")
        zpl.append("================")
        zpl.append("^FS")
        zpl.append("^XZ")                    // End format
        return zpl.toString().toByteArray(Charsets.UTF_8)
    }

    fun buildZplLabel(text: String, width: Int = 400, height: Int = 300): ByteArray {
        val lines = text.split("\n")
        val zpl = StringBuilder()
        zpl.append("^XA")
        zpl.append("^LH0,0")                 // No margin
        zpl.append("^CF0,30")
        
        var yPos = 0
        lines.forEach { line ->
            zpl.append("^FO0,$yPos")         // x=0 for left edge
            zpl.append("^FD$line^FS")
            yPos += 35
        }
        
        zpl.append("^XZ")
        return zpl.toString().toByteArray(Charsets.UTF_8)
    }

    // ==================== TSPL Commands (TSC) ====================
    
    fun buildTsplTestLabel(): ByteArray {
        // TSC TSPL2 commands - must use exact format
        val commands = mutableListOf<String>()
        
        // Basic setup commands
        commands.add("SIZE 60 mm,40 mm")          // Label size (width x height)
        commands.add("GAP 3 mm,0 mm")             // Gap between labels
        commands.add("DIRECTION 1")               // Print direction
        commands.add("REFERENCE 0,0")             // Reference point
        commands.add("OFFSET 0 mm")               // Offset
        commands.add("SET PEEL OFF")              // Peel mode off
        commands.add("SET CUTTER OFF")            // Cutter off
        commands.add("SET TEAR ON")               // Tear mode on
        commands.add("CLS")                       // Clear image buffer
        
        // Print text - TEXT x,y,"font",rotation,x-mul,y-mul,"content"
        // x=0 starts from left edge with no margin
        commands.add("TEXT 0,10,\"3\",0,1,1,\"PRINTER TEST\"")
        commands.add("TEXT 0,50,\"2\",0,1,1,\"TSC Label OK\"")
        commands.add("TEXT 0,80,\"1\",0,1,1,\"==================\"")
        commands.add("TEXT 0,110,\"2\",0,1,1,\"Test Complete\"")
        
        // Print command - PRINT m[,n] where m=sets, n=copies per set
        commands.add("PRINT 1,1")
        commands.add("EOP")  // End of print job - releases printer

        // Join with CR+LF and add final CR+LF
        val tspl = commands.joinToString("\r\n") + "\r\n"
        return tspl.toByteArray(Charsets.US_ASCII)
    }

    fun buildTsplLabel(text: String, widthMm: Int = 60, heightMm: Int = 40): ByteArray {
        val lines = text.split("\n").filter { it.isNotBlank() }
        val commands = mutableListOf<String>()

        commands.add("SIZE $widthMm mm,$heightMm mm")
        commands.add("GAP 3 mm,0 mm")
        commands.add("DIRECTION 1")
        commands.add("REFERENCE 0,0")  // Start from origin
        commands.add("CLS")

        var yPos = 0
        lines.forEach { line ->
            // Escape quotes in the text
            val escapedLine = line.replace("\"", "'")
            // x=0 for no left margin
            commands.add("TEXT 0,$yPos,\"2\",0,1,1,\"$escapedLine\"")
            yPos += 30
        }

        commands.add("PRINT 1,1")
        commands.add("EOP")  // End of print job - releases printer
        
        val tspl = commands.joinToString("\r\n") + "\r\n"
        return tspl.toByteArray(Charsets.US_ASCII)
    }
    
    // Simple TSPL test - minimal commands with paper feed after print
    fun buildTsplSimpleTest(text: String = "TEST"): ByteArray {
        val wrappedLines = wrapText(text, 40)
        val lineHeight = 25
        val totalHeight = (wrappedLines.size * lineHeight) + 20
        
        val commands = StringBuilder()
        commands.append("SIZE 102 mm,$totalHeight dot\r\n")
        commands.append("GAP 0 mm,0 mm\r\n")
        commands.append("DIRECTION 1\r\n")
        commands.append("REFERENCE 0,0\r\n")
        commands.append("CLS\r\n")
        
        var yPos = 0
        wrappedLines.forEach { line ->
            val escapedLine = line.replace("\"", "'").trim()
            if (escapedLine.isNotEmpty()) {
                commands.append("TEXT 0,$yPos,\"1\",0,1,1,\"$escapedLine\"\r\n")
            }
            yPos += lineHeight
        }
        
        commands.append("PRINT 1,1\r\n")
        commands.append("FORMFEED\r\n")  // Feed paper to end after print
        commands.append("EOP\r\n")
        return commands.toString().toByteArray(Charsets.US_ASCII)
    }

    // Helper function to wrap text into lines
    private fun wrapText(text: String, maxCharsPerLine: Int): List<String> {
        val wrappedLines = mutableListOf<String>()
        text.split("\n").forEach { paragraph ->
            if (paragraph.length <= maxCharsPerLine) {
                wrappedLines.add(paragraph)
            } else {
                var remaining = paragraph
                while (remaining.isNotEmpty()) {
                    val chunk = if (remaining.length <= maxCharsPerLine) {
                        remaining
                    } else {
                        remaining.substring(0, maxCharsPerLine)
                    }
                    wrappedLines.add(chunk)
                    remaining = remaining.drop(chunk.length)
                }
            }
        }
        return wrappedLines
    }

    // Print bitmap/image data for TSC printers (for View printing)
    fun buildTsplBitmap(bitmapData: ByteArray, widthBytes: Int, height: Int): ByteArray {
        val commands = StringBuilder()
        commands.append("SIZE 102 mm,$height dot\r\n")
        commands.append("GAP 0 mm,0 mm\r\n")
        commands.append("DIRECTION 1\r\n")
        commands.append("CLS\r\n")
        // BITMAP x,y,width(bytes),height,mode,data
        commands.append("BITMAP 0,0,$widthBytes,$height,0,")
        
        val result = mutableListOf<Byte>()
        commands.toString().toByteArray(Charsets.US_ASCII).forEach { result.add(it) }
        bitmapData.forEach { result.add(it) }
        "\r\nPRINT 1,1\r\nFORMFEED\r\nEOP\r\n".toByteArray(Charsets.US_ASCII).forEach { result.add(it) }
        
        return result.toByteArray()
    }

    // Print long text with automatic line wrapping for TSC printers
    fun buildTsplLongText(text: String, maxCharsPerLine: Int = 40): ByteArray {
        // Split long text into lines that fit label width
        val wrappedLines = mutableListOf<String>()
        
        // First split by newlines, then wrap each line if too long
        text.split("\n").forEach { paragraph ->
            if (paragraph.length <= maxCharsPerLine) {
                wrappedLines.add(paragraph)
            } else {
                // Wrap long lines
                var remaining = paragraph
                while (remaining.isNotEmpty()) {
                    val chunk = if (remaining.length <= maxCharsPerLine) {
                        remaining
                    } else {
                        remaining.substring(0, maxCharsPerLine)
                    }
                    wrappedLines.add(chunk)
                    remaining = remaining.drop(chunk.length)
                }
            }
        }
        
        val lineHeight = 25
        val totalHeight = (wrappedLines.size * lineHeight) + 10
        
        val commands = StringBuilder()
        commands.append("SIZE 102 mm,$totalHeight dot\r\n")
        commands.append("GAP 0 mm,0 mm\r\n")
        commands.append("DIRECTION 1\r\n")
        commands.append("REFERENCE 0,0\r\n")
        commands.append("CLS\r\n")
        
        var yPos = 0
        wrappedLines.forEach { line ->
            val escapedLine = line.replace("\"", "'").trim()
            if (escapedLine.isNotEmpty()) {
                commands.append("TEXT 0,$yPos,\"1\",0,1,1,\"$escapedLine\"\r\n")
            }
            yPos += lineHeight
        }
        
        commands.append("PRINT 1,1\r\n")
        commands.append("EOP\r\n")
        return commands.toString().toByteArray(Charsets.US_ASCII)
    }

    // ==================== ESC/POS Bitmap Commands ====================
    // For standard receipt printers (USB, Bluetooth, etc.)
    
    fun buildEscPosBitmap(bitmap: android.graphics.Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        
        // ESC/POS uses 8 vertical dots per byte
        val bytesPerRow = (width + 7) / 8
        
        val data = mutableListOf<Byte>()
        
        // Initialize printer
        data.add(0x1B) // ESC
        data.add('@'.code.toByte()) // @
        
        // Set line spacing to 0 for bitmap
        data.add(0x1B) // ESC
        data.add('3'.code.toByte())
        data.add(0) // 0 line spacing
        
        // Process bitmap in 24-dot high strips (3 bytes vertical)
        var y = 0
        while (y < height) {
            // ESC * m nL nH - Select bit image mode
            // m = 33 (24-dot double-density)
            data.add(0x1B)
            data.add('*'.code.toByte())
            data.add(33) // 24-dot double density
            data.add((width and 0xFF).toByte()) // nL
            data.add(((width shr 8) and 0xFF).toByte()) // nH
            
            // Send 24 rows of pixels (3 bytes per column)
            for (x in 0 until width) {
                for (k in 0 until 3) { // 3 bytes = 24 dots vertical
                    var slice = 0
                    for (b in 0 until 8) {
                        val yy = y + k * 8 + b
                        if (yy < height) {
                            val pixel = bitmap.getPixel(x, yy)
                            val gray = (android.graphics.Color.red(pixel) +
                                       android.graphics.Color.green(pixel) +
                                       android.graphics.Color.blue(pixel)) / 3
                            if (gray < 128) {
                                slice = slice or (1 shl (7 - b))
                            }
                        }
                    }
                    data.add(slice.toByte())
                }
            }
            
            // Line feed
            data.add(0x0A)
            y += 24
        }
        
        // Reset line spacing
        data.add(0x1B)
        data.add('2'.code.toByte())
        
        // Feed paper and cut (partial cut)
        data.add(0x1B)
        data.add('d'.code.toByte())
        data.add(3) // Feed 3 lines
        
        return data.toByteArray()
    }

    // ==================== Universal Raw Commands ====================
    // These work with most label printers by sending simple text + form feed
    
    fun buildUniversalTestLabel(): ByteArray {
        val data = mutableListOf<Byte>()
        
        // ESC @ - Initialize printer (works on many printers)
        data.add(0x1B)
        data.add('@'.code.toByte())
        
        // Simple text
        "PRINTER TEST\r\n".toByteArray().forEach { data.add(it) }
        "Label Print OK\r\n".toByteArray().forEach { data.add(it) }
        "==============\r\n".toByteArray().forEach { data.add(it) }
        "\r\n".toByteArray().forEach { data.add(it) }
        
        // Form Feed (FF) - eject label
        data.add(0x0C)
        
        // ESC @ - Reset printer after job
        data.add(0x1B)
        data.add('@'.code.toByte())
        
        return data.toByteArray()
    }

    fun buildUniversalLabel(text: String): ByteArray {
        val data = mutableListOf<Byte>()
        
        // ESC @ - Initialize
        data.add(0x1B)
        data.add('@'.code.toByte())
        
        // Text content
        text.toByteArray(Charsets.UTF_8).forEach { data.add(it) }
        "\r\n\r\n".toByteArray().forEach { data.add(it) }
        
        // Form Feed
        data.add(0x0C)
        
        // Reset
        data.add(0x1B)
        data.add('@'.code.toByte())
        
        return data.toByteArray()
    }

    // ==================== EPL Commands (Eltron/older Zebra) ====================
    
    fun buildEplTestLabel(): ByteArray {
        val epl = StringBuilder()
        epl.append("\nN\n")                      // Clear buffer
        epl.append("R0,0\n")                     // Reference point at origin
        epl.append("q400\n")                     // Label width
        epl.append("Q300,24\n")                  // Label height, gap
        epl.append("A0,0,0,3,1,1,N,\"PRINTER TEST\"\n")   // x=0 for left edge
        epl.append("A0,40,0,2,1,1,N,\"Label Print OK\"\n")
        epl.append("A0,70,0,2,1,1,N,\"================\"\n")
        epl.append("P1\n")                       // Print 1 label
        return epl.toString().toByteArray(Charsets.UTF_8)
    }

    fun buildEplLabel(text: String): ByteArray {
        val lines = text.split("\n")
        val epl = StringBuilder()
        epl.append("\nN\n")
        epl.append("R0,0\n")                     // Reference point at origin
        epl.append("q400\n")
        epl.append("Q300,24\n")
        
        var yPos = 0
        lines.forEach { line ->
            epl.append("A0,$yPos,0,2,1,1,N,\"$line\"\n")  // x=0 for left edge
            yPos += 30
        }
        
        epl.append("P1\n")
        return epl.toString().toByteArray(Charsets.UTF_8)
    }

    // ==================== CPCL Commands (Mobile printers) ====================
    
    fun buildCpclTestLabel(): ByteArray {
        val cpcl = StringBuilder()
        cpcl.append("! 0 200 200 300 1\r\n")     // Width, height, qty
        cpcl.append("CENTER\r\n")
        cpcl.append("TEXT 4 0 0 20 PRINTER TEST\r\n")
        cpcl.append("TEXT 4 0 0 60 Label Print OK\r\n")
        cpcl.append("TEXT 4 0 0 90 ================\r\n")
        cpcl.append("BARCODE 128 1 1 80 0 130 123456789\r\n")
        cpcl.append("FORM\r\n")
        cpcl.append("PRINT\r\n")
        return cpcl.toString().toByteArray(Charsets.UTF_8)
    }

    // ==================== ESC/POS for Label (some support this) ====================
    
    fun buildEscPosTestLabel(): ByteArray {
        val ESC = 0x1B.toByte()
        val GS = 0x1D.toByte()
        val LF = 0x0A.toByte()
        
        val data = mutableListOf<Byte>()
        
        // Initialize
        data.add(ESC)
        data.add('@'.code.toByte())
        
        // Center align
        data.add(ESC)
        data.add('a'.code.toByte())
        data.add(1)
        
        // Bold on
        data.add(ESC)
        data.add('E'.code.toByte())
        data.add(1)
        
        "PRINTER TEST".toByteArray().forEach { data.add(it) }
        data.add(LF)
        
        // Bold off
        data.add(ESC)
        data.add('E'.code.toByte())
        data.add(0)
        
        "Label Print OK".toByteArray().forEach { data.add(it) }
        data.add(LF)
        "================".toByteArray().forEach { data.add(it) }
        data.add(LF)
        data.add(LF)
        
        // Feed and cut
        data.add(LF)
        data.add(LF)
        data.add(LF)
        data.add(GS)
        data.add('V'.code.toByte())
        data.add(66)
        data.add(3)
        
        return data.toByteArray()
    }

    fun buildEscPosLabel(text: String): ByteArray {
        val ESC = 0x1B.toByte()
        val GS = 0x1D.toByte()
        val LF = 0x0A.toByte()
        
        val data = mutableListOf<Byte>()
        
        // Initialize
        data.add(ESC)
        data.add('@'.code.toByte())
        
        text.toByteArray().forEach { data.add(it) }
        data.add(LF)
        data.add(LF)
        data.add(LF)
        
        // Cut
        data.add(GS)
        data.add('V'.code.toByte())
        data.add(66)
        data.add(3)
        
        return data.toByteArray()
    }

    // ==================== Raw text (simplest - works on some printers) ====================
    
    fun buildRawTestLabel(): ByteArray {
        val text = StringBuilder()
        text.append("PRINTER TEST\r\n")
        text.append("Label Print OK\r\n")
        text.append("================\r\n")
        text.append("123456789\r\n")
        text.append("\r\n\r\n\r\n")
        return text.toString().toByteArray(Charsets.UTF_8)
    }

    fun buildRawLabel(text: String): ByteArray {
        return (text + "\r\n\r\n\r\n").toByteArray(Charsets.UTF_8)
    }

    // ==================== Auto-detect and build ====================

    fun getTestLabelData(language: LabelLanguage): ByteArray {
        return when (language) {
            LabelLanguage.ZPL -> buildZplTestLabel()
            LabelLanguage.TSPL -> buildTsplTestLabel()
            LabelLanguage.EPL -> buildEplTestLabel()
            LabelLanguage.CPCL -> buildCpclTestLabel()
            LabelLanguage.ESC_POS -> buildEscPosTestLabel()
        }
    }

    fun getLabelData(text: String, language: LabelLanguage): ByteArray {
        return when (language) {
            LabelLanguage.ZPL -> buildZplLabel(text)
            LabelLanguage.TSPL -> buildTsplLabel(text)
            LabelLanguage.EPL -> buildEplLabel(text)
            LabelLanguage.CPCL -> buildRawLabel(text) // Simplified
            LabelLanguage.ESC_POS -> buildEscPosLabel(text)
        }
    }
}
