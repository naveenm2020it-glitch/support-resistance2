package com.naveen.supportresistance
import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; setPadding(32,32,32,32); setBackgroundColor(Color.rgb(13,24,42)) }
  val title=TextView(this).apply { text="SUPPORT & RESISTANCE"; textSize=24f; setTextColor(Color.WHITE); gravity=Gravity.CENTER }
  val subtitle=TextView(this).apply { text="Android project initialized\n\nBuild workflow is ready."; textSize=18f; setTextColor(Color.rgb(230,230,230)); gravity=Gravity.CENTER; setPadding(0,32,0,0) }
  root.addView(title); root.addView(subtitle); setContentView(root)
 }
}
