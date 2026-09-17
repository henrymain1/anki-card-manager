package com.borderless.ankicards

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * No-UI trampoline that receives Android SEND intents (e.g. from Pleco's share
 * sheet), pushes the shared word into the queue, and exits immediately.
 *
 * Because we never call [setContent] and we [finish] in [onCreate], the user
 * stays in the source app — only seeing a brief Toast confirming the queue.
 *
 * The Activity uses Theme.NoDisplay (declared in the Manifest) to avoid
 * flashing a window during the handoff.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare(intent)
        finish()
    }

    private fun handleShare(intent: Intent?) {
        if (intent == null) return
        if (intent.action != Intent.ACTION_SEND) return
        if (intent.type != "text/plain") return

        val raw = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        Log.d(TAG, "Received share. EXTRA_SUBJECT=\"$subject\"  EXTRA_TEXT=\"$raw\"")

        if (raw.isBlank()) return

        // Pleco / browsers sometimes share extra context. Take just the first
        // line and trim leading/trailing punctuation/whitespace.
        val cleaned = raw.lineSequence().firstOrNull().orEmpty()
            .trim()
            .trim { it.isWhitespace() || it == '"' || it == '「' || it == '」' }
        if (cleaned.isBlank()) return

        Log.d(TAG, "Enqueuing cleaned word: \"$cleaned\"")
        val container = (application as AnkiCardsApp).container
        val newId = container.queueRepository.enqueue(cleaned)
        if (newId != null) {
            Toast.makeText(this, "Queued \"$cleaned\"", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "AnkiCards.Share"
    }
}
