package dev.droiduse.agent

import java.text.Normalizer

/** A single OCR character error is tolerated only at the same, unambiguous location. */
object TextTarget {
    fun resolve(name: String,x: Int,y: Int,rows: List<ReadingProgress.Row>): ReadingProgress.Row? {
        fun distance(r: ReadingProgress.Row)=(r.x-x).toLong()*(r.x-x)+(r.y-y).toLong()*(r.y-y)
        rows.filter { it.text==name }.minByOrNull(::distance)?.let { return it }
        fun normalize(s: String)=Normalizer.normalize(s,Normalizer.Form.NFKC).filterNot(Char::isWhitespace)
        val expected=normalize(name)
        return rows.distinct().filter { row ->
            val actual=normalize(row.text)
            distance(row)<=144 && actual.length==expected.length &&
                (actual==expected || (expected.length>=6 && actual.zip(expected).count { it.first!=it.second }==1))
        }.singleOrNull()
    }
}
