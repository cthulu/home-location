package nl.freshlytyped.homelocation

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Minimal launcher activity for the location app foundation. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
