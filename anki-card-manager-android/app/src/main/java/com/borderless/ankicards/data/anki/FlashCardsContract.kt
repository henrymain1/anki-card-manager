package com.borderless.ankicards.data.anki

import android.net.Uri

/**
 * Constants mirroring [com.ichi2.anki.FlashCardsContract] from the AnkiDroid app.
 * Pulled in directly so we don't pull in the JitPack `Anki-Android` artifact.
 *
 * Reference: https://github.com/ankidroid/Anki-Android/wiki/AnkiDroid-API
 */
object FlashCardsContract {

    const val AUTHORITY = "com.ichi2.anki.flashcards"
    const val PERMISSION_READ_WRITE = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

    /** Field separator AnkiDroid uses inside the FLDS column. */
    const val FIELD_SEPARATOR: Char = ''

    object Note {
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/notes")

        const val ID = "_id"
        const val GUID = "guid"
        const val MID = "mid"          // model id
        const val DECK_ID_QUERY_PARAM = "deckId"
        const val FLDS = "flds"        // -separated field values
        const val TAGS = "tags"
        const val SFLD = "sfld"        // sort field
    }

    object Model {
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/models")

        const val ID = "_id"
        const val NAME = "name"
        const val FIELD_NAMES = "field_names"     // newline-separated
        const val NUM_FIELDS = "num_fields"
        const val DECK_ID = "deck_id"
        /** Shared CSS for every card template on this note type. */
        const val CSS = "css"
        /**
         * On INSERT, ;-separated list of field names that defines the note
         * type's initial field array. Required and must be non-empty —
         * AnkiDroid rejects model inserts with no field list.
         */
        const val FIELD_NAMES_INSERT = "field_names"
        /**
         * On INSERT, the number of card templates to create on this model.
         * Required by AnkiDroid (the contract validates it can't be empty).
         * Each template gets its `qfmt`/`afmt`/name set in a follow-up update
         * via the [CardTemplate] sub-URI.
         */
        const val NUM_CARDS = "num_cards"
    }

    /**
     * One card template on a note type, addressed via
     * `content://.../models/<modelId>/templates/<ord>`. The collection
     * sub-URI (without an ord) lists every template on the model.
     *
     * Each template owns its own front and back HTML (`qfmt` / `afmt`) and a
     * name. The CSS column on the parent model is shared across templates.
     */
    object CardTemplate {
        /** Sub-path appended to a model URI to address the template collection. */
        const val URI_PATH = "templates"
        const val NAME = "card_template_name"
        const val ORD = "ord"
        /**
         * The note-type (model) id this template belongs to.
         *
         * Defined for completeness — but DO NOT put this in the ContentValues
         * when calling `update` on a template URI. AnkiDroid's provider reads
         * it only to validate you're not trying to reassign the template to
         * another note type, and throws "Updates to mid or ord are not allowed"
         * if it's present. The model id is identified from the URI path
         * segment instead.
         */
        const val MODEL_ID = "model_id"
        /**
         * Question / front HTML, with {{FieldName}} placeholders.
         *
         * Note: the ContentValues key is `question_format`, NOT `qfmt`. The
         * latter is the internal SQLite column name AnkiDroid stores it under;
         * the public ContentProvider key is the longer form. Mixing them up
         * (which we did originally) means the update silently does nothing.
         */
        const val QUESTION_FORMAT = "question_format"
        /** Answer / back HTML, with {{FieldName}} and {{FrontSide}} placeholders. */
        const val ANSWER_FORMAT = "answer_format"
    }

    /**
     * Media collection. Insert with [FILE_URI] (a content:// or file:// URI that
     * AnkiDroid can read) plus an optional [PREFERRED_NAME]; the returned URI's
     * last path segment is the actual filename AnkiDroid stored.
     */
    object AnkiMedia {
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/media")
        const val FILE_URI = "file_uri"
        const val PREFERRED_NAME = "preferred_name"
    }

    /**
     * Cards belonging to a note, addressed via
     * `content://.../notes/<noteId>/cards/<ord>`. The DECK_ID column on each row
     * is what we update to move a note's cards into a specific deck.
     */
    object Card {
        const val NOTE_ID = "note_id"
        const val CARD_ORD = "ord"
        const val DECK_ID = "deck_id"
        const val CARD_NAME = "card_name"
    }

    object Deck {
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/decks")

        const val DECK_ID = "deck_id"
        const val DECK_NAME = "deck_name"
    }
}
