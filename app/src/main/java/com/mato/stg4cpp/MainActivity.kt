package com.mato.stg4cpp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.mato.stg4cpp.databinding.ActivityMainBinding
import org.json.JSONObject

@Suppress("")
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)


        val rest = Restaurant(5.0f).toJSONObject()

        runCatching { Restaurant::class.fromJSONObject(rest.getOrNull()!!).info() }
            .onFailure { it.stackTraceToString().info() }

        runCatching { Restaurant::class.fromJSONObject(JSONObject()).info() }
            .onFailure { it.stackTraceToString().info() }

        Restaurant(2.0f).SayHello()

        // Example of a call to a native method
        binding.sampleText.text = rest.toString()
    }
}