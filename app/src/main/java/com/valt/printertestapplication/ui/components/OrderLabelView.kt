package com.valt.printertestapplication.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.valt.printertestapplication.R
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale

data class OrderItem(
    val name: String,
    val quantity: Int,
    val price: Double
)

data class OrderData(
    val orderId: String,
    val date: String,
    val items: List<OrderItem>,
    val subtotal: Double,
    val tax: Double,
    val total: Double,
    val customerName: String = ""
)

@Composable
fun OrderLabelView(
    order: OrderData,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(androidx.compose.ui.graphics.Color.White)
            .padding(8.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Logo/Image Header
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "Logo",
            modifier = Modifier.size(48.dp)
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // Header
        Text(
            text = "ORDER RECEIPT",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = androidx.compose.ui.graphics.Color.Black
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Text(
            text = "Order #${order.orderId}",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = androidx.compose.ui.graphics.Color.Black
        )
        
        Text(
            text = order.date,
            fontSize = 10.sp,
            color = androidx.compose.ui.graphics.Color.DarkGray
        )
        
        if (order.customerName.isNotEmpty()) {
            Text(
                text = "Customer: ${order.customerName}",
                fontSize = 10.sp,
                color = androidx.compose.ui.graphics.Color.Black
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = androidx.compose.ui.graphics.Color.Black, thickness = 1.dp)
        Spacer(modifier = Modifier.height(4.dp))
        
        // Items header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Item",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = androidx.compose.ui.graphics.Color.Black,
                modifier = Modifier.weight(2f)
            )
            Text(
                text = "Qty",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = androidx.compose.ui.graphics.Color.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(0.5f)
            )
            Text(
                text = "Price",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = androidx.compose.ui.graphics.Color.Black,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // Items
        order.items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.name,
                    fontSize = 10.sp,
                    color = androidx.compose.ui.graphics.Color.Black,
                    modifier = Modifier.weight(2f)
                )
                Text(
                    text = "${item.quantity}",
                    fontSize = 10.sp,
                    color = androidx.compose.ui.graphics.Color.Black,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(0.5f)
                )
                Text(
                    text = "$${String.format("%.2f", item.price)}",
                    fontSize = 10.sp,
                    color = androidx.compose.ui.graphics.Color.Black,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(color = androidx.compose.ui.graphics.Color.Black, thickness = 1.dp)
        Spacer(modifier = Modifier.height(4.dp))
        
        // Totals
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Subtotal:", fontSize = 10.sp, color = androidx.compose.ui.graphics.Color.Black)
            Text(text = "$${String.format("%.2f", order.subtotal)}", fontSize = 10.sp, color = androidx.compose.ui.graphics.Color.Black)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Tax:", fontSize = 10.sp, color = androidx.compose.ui.graphics.Color.Black)
            Text(text = "$${String.format("%.2f", order.tax)}", fontSize = 10.sp, color = androidx.compose.ui.graphics.Color.Black)
        }
        
        Spacer(modifier = Modifier.height(2.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "TOTAL:",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = androidx.compose.ui.graphics.Color.Black
            )
            Text(
                text = "$${String.format("%.2f", order.total)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = androidx.compose.ui.graphics.Color.Black
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Thank you!",
            fontSize = 10.sp,
            color = androidx.compose.ui.graphics.Color.Black
        )
    }
}

// Utility to create order bitmap directly (without Compose View capture)
object OrderBitmapGenerator {
    
    fun createOrderBitmap(order: OrderData, widthPx: Int = 384, context: Context? = null, logoResId: Int? = null): Bitmap {
        // Text paint - BLACK text on WHITE background
        val paint = Paint().apply {
            isAntiAlias = true
            color = Color.BLACK  // BLACK text
            textSize = 24f
        }
        
        val boldPaint = Paint(paint).apply {
            typeface = Typeface.DEFAULT_BOLD
        }
        
        val smallPaint = Paint(paint).apply {
            textSize = 20f
        }
        
        val titlePaint = Paint(boldPaint).apply {
            textSize = 32f
        }
        
        // Logo dimensions
        val logoHeight = if (context != null && logoResId != null) 64 else 0
        
        // Calculate height based on content
        val lineHeight = 28
        val titleHeight = 40
        val padding = 16
        val itemCount = order.items.size
        
        val totalHeight = padding + // top padding
                logoHeight + // Logo image
                (if (logoHeight > 0) 8 else 0) + // spacing after logo
                titleHeight + // ORDER RECEIPT
                lineHeight + // Order #
                lineHeight + // Date
                (if (order.customerName.isNotEmpty()) lineHeight else 0) + // Customer
                padding + // spacing
                lineHeight + // header
                (itemCount * lineHeight) + // items
                padding + // spacing
                lineHeight + // subtotal
                lineHeight + // tax
                lineHeight + // total
                padding + // spacing
                lineHeight + // thank you
                padding // bottom padding
        
        val bitmap = createBitmap(widthPx, totalHeight)
        val canvas = Canvas(bitmap)
        
        // WHITE background
        canvas.drawColor(Color.WHITE)
        
        var y = padding.toFloat()
        
        // Draw logo if provided
        if (context != null && logoResId != null) {
            try {
                val logoBitmap = BitmapFactory.decodeResource(context.resources, logoResId)
                if (logoBitmap != null) {
                    // Scale logo to fit
                    val logoSize = logoHeight
                    val scaledLogo = logoBitmap.scale(logoSize, logoSize)
                    val logoX = (widthPx - logoSize) / 2f
                    canvas.drawBitmap(scaledLogo, logoX, y, null)
                    y += logoHeight + 8
                    
                    if (scaledLogo != logoBitmap) {
                        scaledLogo.recycle()
                    }
                }
            } catch (e: Exception) {
                // Logo loading failed, continue without it
                android.util.Log.e("OrderBitmapGenerator", "Failed to load logo", e)
            }
        }
        
        // Title - BLACK text
        val titleText = "ORDER RECEIPT"
        val titleWidth = titlePaint.measureText(titleText)
        canvas.drawText(titleText, (widthPx - titleWidth) / 2, y + 28, titlePaint)
        y += titleHeight
        
        // Order ID
        val orderText = "Order #${order.orderId}"
        val orderWidth = boldPaint.measureText(orderText)
        canvas.drawText(orderText, (widthPx - orderWidth) / 2, y + 20, boldPaint)
        y += lineHeight
        
        // Date
        val dateWidth = smallPaint.measureText(order.date)
        canvas.drawText(order.date, (widthPx - dateWidth) / 2, y + 18, smallPaint)
        y += lineHeight
        
        // Customer name
        if (order.customerName.isNotEmpty()) {
            val custText = "Customer: ${order.customerName}"
            val custWidth = smallPaint.measureText(custText)
            canvas.drawText(custText, (widthPx - custWidth) / 2, y + 18, smallPaint)
            y += lineHeight
        }
        
        y += 8
        
        // Divider
        canvas.drawLine(8f, y, widthPx - 8f, y, paint)
        y += padding
        
        // Items header
        canvas.drawText("Item", 8f, y + 18, boldPaint)
        canvas.drawText("Qty", widthPx * 0.6f, y + 18, boldPaint)
        canvas.drawText("Price", widthPx * 0.8f, y + 18, boldPaint)
        y += lineHeight
        
        // Items
        order.items.forEach { item ->
            canvas.drawText(item.name.take(20), 8f, y + 18, smallPaint)
            canvas.drawText("${item.quantity}", widthPx * 0.65f, y + 18, smallPaint)
            canvas.drawText("$${String.format("%.2f", item.price)}", widthPx * 0.78f, y + 18, smallPaint)
            y += lineHeight
        }
        
        y += 4
        
        // Divider
        canvas.drawLine(8f, y, widthPx - 8f, y, paint)
        y += padding
        
        // Subtotal
        canvas.drawText("Subtotal:", 8f, y + 18, smallPaint)
        val subtotalText = "$${String.format("%.2f", order.subtotal)}"
        canvas.drawText(subtotalText, widthPx - 8 - smallPaint.measureText(subtotalText), y + 18, smallPaint)
        y += lineHeight
        
        // Tax
        canvas.drawText("Tax:", 8f, y + 18, smallPaint)
        val taxText = "$${String.format("%.2f", order.tax)}"
        canvas.drawText(taxText, widthPx - 8 - smallPaint.measureText(taxText), y + 18, smallPaint)
        y += lineHeight
        
        // Total
        canvas.drawText("TOTAL:", 8f, y + 22, boldPaint)
        val totalText = "$${String.format("%.2f", order.total)}"
        canvas.drawText(totalText, widthPx - 8 - boldPaint.measureText(totalText), y + 22, boldPaint)
        y += lineHeight + 8
        
        // Thank you
        val thankText = "Thank you!"
        val thankWidth = smallPaint.measureText(thankText)
        canvas.drawText(thankText, (widthPx - thankWidth) / 2, y + 18, smallPaint)
        
        return bitmap
    }
}

// Utility to capture Compose OrderLabelView as bitmap
object ComposeViewCapture {
    
    /**
     * Creates a bitmap from OrderLabelView by rendering it using Android View system
     * This captures the actual Compose UI as it appears on screen
     */
    fun captureOrderLabelView(
        context: Context,
        order: OrderData,
        widthPx: Int = 384
    ): Bitmap {
        // Create a ComposeView and render the OrderLabelView
        val composeView = ComposeView(context).apply {
            setContent {
                OrderLabelView(
                    order = order,
                    modifier = Modifier
                        .width((widthPx / context.resources.displayMetrics.density).dp)
                        .background(androidx.compose.ui.graphics.Color.White)
                )
            }
        }
        
        // Measure and layout the view
        val widthSpec = android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY)
        val heightSpec = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        
        composeView.measure(widthSpec, heightSpec)
        val measuredHeight = composeView.measuredHeight
        composeView.layout(0, 0, widthPx, measuredHeight)
        
        // Create bitmap and draw
        val bitmap = createBitmap(widthPx, measuredHeight)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)  // White background
        composeView.draw(canvas)
        
        return bitmap
    }
}
