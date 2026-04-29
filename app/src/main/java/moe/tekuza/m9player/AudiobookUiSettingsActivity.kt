package moe.tekuza.m9player

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class AudiobookUiSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, BookReaderActivity::class.java).apply {
                putExtra(BookReaderActivity.EXTRA_BOOK_TITLE, "第一章   この自称女神と異世界転生を!")
                putExtra(BookReaderActivity.EXTRA_UI_TEST_MODE, true)
            }
        )
        finish()
    }
}
