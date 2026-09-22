package com.jarvis.ai

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var input: EditText
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var prefs: SharedPreferences
    private var speechRecognizer: SpeechRecognizer? = null
    private var mediaPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val groqModel = "openai/gpt-oss-20b"
    private val groqUrl = "https://api.groq.com/openai/v1/chat/completions"
    private val fishUrl = "https://api.fish.audio/v1/tts"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        input = findViewById(R.id.inputText)
        chat = findViewById(R.id.chatContainer)
        scroll = findViewById(R.id.chatScroll)
        status = findViewById(R.id.statusText)
        prefs = getSharedPreferences("jarvis_settings", MODE_PRIVATE)

        findViewById<View>(R.id.sendButton).setOnClickListener { sendTypedMessage() }
        findViewById<View>(R.id.micButton).setOnClickListener { startVoiceInput() }
        findViewById<View>(R.id.settingsButton).setOnClickListener { showSettings() }

        addMessage("JARVIS", "Systems online. Enter your Groq API key and Fish Audio settings, then talk to me.")
        setupSpeechRecognizer()
    }

    private fun sendTypedMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        sendToGroq(text)
    }

    private fun sendToGroq(userText: String) {
        val key = prefs.getString("groq_key", "")?.trim().orEmpty()
        if (key.isEmpty()) {
            showSettings()
            return
        }
        addMessage("YOU", userText)
        status.text = "THINKING"
        Thread {
            try {
                val body = JSONObject().apply {
                    put("model", groqModel)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", "You are JARVIS, a concise, helpful personal AI assistant. Reply in the same language the user used unless they request another language. Do not claim to have performed actions you cannot perform. Keep answers natural for voice playback.")
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", userText)
                        })
                    })
                    put("temperature", 0.7)
                    put("max_tokens", 700)
                }
                val response = postJson(groqUrl, body.toString(), mapOf("Authorization" to "Bearer $key"))
                val json = JSONObject(response)
                val reply = json.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
                mainHandler.post {
                    addMessage("JARVIS", reply)
                    status.text = "ONLINE"
                    speakWithFish(reply)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    status.text = "ERROR"
                    addMessage("JARVIS", "I couldn't reach Groq. Check the API key and internet connection.\n\n${e.message ?: "Unknown error"}")
                }
            }
        }.start()
    }

    private fun speakWithFish(text: String) {
        val fishKey = prefs.getString("fish_key", "")?.trim().orEmpty()
        if (fishKey.isEmpty()) return
        val voiceId = prefs.getString("fish_voice", "")?.trim().orEmpty()

        Thread {
            try {
                val body = JSONObject().apply {
                    put("text", text)
                    put("format", "mp3")
                    if (voiceId.isNotEmpty()) put("reference_id", voiceId)
                }
                val conn = URL(fishUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $fishKey")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("model", "s2.1-pro-free")
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException("Fish Audio HTTP $code")
                val bytes = BufferedInputStream(conn.inputStream).use { inputStream ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = inputStream.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
                val file = File.createTempFile("jarvis_voice_", ".mp3", cacheDir)
                file.writeBytes(bytes)
                mainHandler.post { playAudio(file) }
            } catch (e: Exception) {
                mainHandler.post { status.text = "ONLINE" }
            }
        }.start()
    }

    private fun playAudio(file: File) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(file.absolutePath)
                setOnPreparedListener { it.start(); status.text = "SPEAKING" }
                setOnCompletionListener {
                    status.text = "ONLINE"
                    it.release()
                    file.delete()
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            status.text = "ONLINE"
        }
    }

    private fun postJson(urlString: String, json: String, headers: Map<String, String>): String {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) throw IllegalStateException("HTTP $code: $text")
        return text
    }

    private fun addMessage(who: String, message: String) {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (who == "YOU") Gravity.END else Gravity.START
            setPadding(4, 6, 4, 6)
        }
        val label = TextView(this).apply {
            text = who
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.cyan))
        }
        val bubble = TextView(this).apply {
            text = message
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            setPadding(18, 14, 18, 14)
            background = ContextCompat.getDrawable(this@MainActivity, if (who == "YOU") R.drawable.user_bubble else R.drawable.jarvis_bubble)
        }
        val params = LinearLayout.LayoutParams(-2, -2).apply { topMargin = 2 }
        wrapper.addView(label)
        wrapper.addView(bubble, params)
        chat.addView(wrapper)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
            recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status.text = "LISTENING" }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { status.text = "THINKING" }
                override fun onError(error: Int) { status.text = "ONLINE" }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        input.setText(text)
                        sendTypedMessage()
                    } else status.text = "ONLINE"
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 8)
        }
        val groq = EditText(this).apply {
            hint = "Groq API key"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("groq_key", ""))
        }
        val fish = EditText(this).apply {
            hint = "Fish Audio API key"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("fish_key", ""))
        }
        val voice = EditText(this).apply {
            hint = "Fish voice / reference ID (optional)"
            setText(prefs.getString("fish_voice", ""))
        }
        box.addView(groq)
        box.addView(fish)
        box.addView(voice)

        AlertDialog.Builder(this)
            .setTitle("JARVIS Settings")
            .setMessage("Groq powers the brain. Fish Audio powers the voice. API keys are stored locally on this phone for this personal build.")
            .setView(box)
            .setPositiveButton("SAVE") { _, _ ->
                prefs.edit()
                    .putString("groq_key", groq.text.toString().trim())
                    .putString("fish_key", fish.text.toString().trim())
                    .putString("fish_voice", voice.text.toString().trim())
                    .apply()
                Toast.makeText(this, "JARVIS settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startVoiceInput()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        mediaPlayer?.release()
    }
}
