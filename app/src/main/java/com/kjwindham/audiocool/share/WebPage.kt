package com.kjwindham.audiocool.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.kjwindham.audiocool.data.Note
import com.kjwindham.audiocool.data.Recording
import com.kjwindham.audiocool.data.Session
import com.kjwindham.audiocool.data.SessionRepository
import com.kjwindham.audiocool.data.TimelineMode
import com.kjwindham.audiocool.data.TimelineRow
import com.kjwindham.audiocool.data.timelineRows
import com.kjwindham.audiocool.speakers.Voices
import com.kjwindham.audiocool.summarize.parseChapterKey
import com.kjwindham.audiocool.ui.photoTitle
import com.kjwindham.audiocool.ui.voiceColor
import com.kjwindham.audiocool.util.formatTime
import com.kjwindham.audiocool.util.timeLabel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

/**
 * A session as one web page: its summary, chapters, slides, notes and what was said, and the audio
 * (made small), all in a single .html file that opens in any browser, offline. It reads as it is;
 * with scripts, the times and words play from there and what's playing lights up.
 */
object WebPage {
    /** About how big the page for [session] will be, in bytes. */
    fun estimate(session: Session): Long {
        val audio = session.recordings.sumOf { it.durationMs } / 1000 * SmallAudio.BYTES_PER_SECOND
        val photos = session.notes.count { it.photo != null } * 160_000L
        return (audio + photos) * 4 / 3 + 200_000
    }

    /**
     * Makes the page for [session] in the app's cache, the audio made small first; [onProgress] goes
     * 0..1. Throws if the audio can't be made smaller.
     */
    fun make(context: Context, session: Session, isCancelled: () -> Boolean, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "page").apply {
            deleteRecursively()
            mkdirs()
        }
        val total = session.recordings.sumOf { it.durationMs }.coerceAtLeast(1)
        var done = 0L
        val small = HashMap<String, File>()
        for (rec in session.recordings) {
            val source = SessionRepository.audioFile(session.id, rec)
            if (!source.isFile || source.length() == 0L) continue
            val out = File(dir, "audio-${rec.id}.m4a")
            SmallAudio.encode(source, out, isCancelled) { onProgress(((done + it * rec.durationMs) / total * 0.95f).coerceIn(0f, 0.95f)) }
            done += rec.durationMs
            small[rec.id] = out
        }
        val name = session.title.replace(Regex("[^\\w .-]"), "_").trim().take(60).ifEmpty { "session" }
        val page = File(dir, "$name.html")
        page.outputStream().buffered().use { write(it, session, { small[it.id] }, { photo(SessionRepository.photoFile(session.id, it.photo!!)) }) }
        small.values.forEach { it.delete() }
        onProgress(1f)
        return page
    }

    /** Writes the page: [audio] is a recording's small audio (null leaves it out), [photo] a photo's JPEG. */
    fun write(out: OutputStream, session: Session, audio: (Recording) -> File?, photo: (Note) -> ByteArray?) {
        fun put(s: String) = out.write(s.toByteArray())
        put(head(session))
        put(body(session, photo))
        session.recordings.forEachIndexed { i, rec ->
            val file = audio(rec) ?: return@forEachIndexed
            put("<script type=\"application/octet-stream\" id=\"audio-$i\" data-mime=\"audio/mp4\">")
            // Straight from the file, a piece at a time: the audio is far too big to hold as text.
            Base64.getEncoder().wrap(object : FilterOutputStream(out) {
                override fun close() = flush()
            }).use { encoder -> file.inputStream().use { it.copyTo(encoder) } }
            put("</script>\n")
        }
        put("<script>$SCRIPT</script>\n</body>\n</html>\n")
    }

    private fun head(session: Session) = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="generator" content="AudioCool">
<title>${esc(session.title)}</title>
<style>$STYLE</style>
</head>
<body>
"""

    private fun body(session: Session, photo: (Note) -> ByteArray?): String = buildString {
        val rows = timelineRows(session, TimelineMode.EVERYTHING)
        val slides = rows.count { it is TimelineRow.Photo }
        val notes = session.notes.count { it.photo == null && !it.isMark }
        val minutes = (session.totalDurationMs / 60_000).toInt()
        val facts = listOfNotNull(
            SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date(session.createdAt)),
            (if (minutes == 1) "1 minute" else "$minutes minutes").takeIf { session.totalDurationMs > 0 },
            (if (slides == 1) "1 slide" else "$slides slides").takeIf { slides > 0 },
            (if (notes == 1) "1 note" else "$notes notes").takeIf { notes > 0 },
            (if (session.voices.size == 1) "1 speaker" else "${session.voices.size} speakers").takeIf { session.voices.isNotEmpty() },
        ).joinToString(" · ")
        append("<header>")
        session.folder?.let { append("<div class=\"folder\">${esc(it)}</div>") }
        append("<h1>${esc(session.title)}</h1><div class=\"facts\">${esc(facts)}</div><div class=\"made\">Made with AudioCool · works offline</div></header>\n")
        append(
            "<div id=\"player\" class=\"player\" hidden><button id=\"play\" aria-label=\"Play\">▶</button>" +
                "<span class=\"clock\"><span id=\"time\">00:00</span> / <span id=\"dur\">00:00</span></span>" +
                "<input id=\"seek\" type=\"range\" min=\"0\" max=\"1000\" value=\"0\" aria-label=\"Position\">" +
                "<button id=\"speed\" aria-label=\"Speed\">1×</button></div>\n",
        )
        session.summary?.let { s ->
            append("<section class=\"summary\"><h2>✦ Summary</h2><p>${esc(s.text)}</p>")
            if (s.keyPoints.isNotEmpty()) append("<h3>Key points</h3><ul>${s.keyPoints.joinToString("") { "<li>${esc(it)}</li>" }}</ul>")
            if (s.actionItems.isNotEmpty()) append("<h3>Action items</h3><ul>${s.actionItems.joinToString("") { "<li>${esc(it)}</li>" }}</ul>")
            append("</section>\n")
        }
        val chapters = rows.filterIsInstance<TimelineRow.Summary>()
        if (chapters.size > 1) {
            append("<nav class=\"chapters\"><h2>Chapters</h2><ol>")
            for (c in chapters) {
                val (recId, ms) = parseChapterKey(c.chapterKey) ?: continue
                val rec = session.recordings.indexOfFirst { it.id == recId }
                val title = c.title ?: session.notes.firstOrNull { it.photo != null && it.recId == recId && it.offsetMs == ms }?.let(::photoTitle) ?: "Chapter ${c.number}"
                append("<li><a href=\"#\" data-rec=\"$rec\" data-ms=\"$ms\"><span class=\"t\">${esc(timeLabel(session, recId, ms).orEmpty())}</span>${esc(title)}</a></li>")
            }
            append("</ol></nav>\n")
        }
        append("<main>\n")
        var lastVoice: Int? = null
        for (row in rows) {
            when (row) {
                is TimelineRow.RecordingStart -> if (session.recordings.size > 1) {
                    append("<h2 class=\"part\">Recording ${row.number} · ${esc(SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(row.rec.createdAt)))}</h2>\n")
                }
                is TimelineRow.Photo -> {
                    val note = row.note
                    val title = photoTitle(note)
                    val at = moment(session, note.recId, note.offsetMs)
                    append("<figure class=\"slide\">")
                    append("<figcaption>${link(at, "Slide ${row.number}" + (at?.third?.let { " · $it" } ?: ""))}")
                    title?.let { append("<b>${esc(it)}</b>") }
                    append("</figcaption>")
                    photo(note)?.let { append("<img alt=\"${esc(title ?: "Photo")}\" src=\"data:image/jpeg;base64,${Base64.getEncoder().encodeToString(it)}\">") }
                    if (note.text.isNotBlank() && note.text.trim() != title) append("<p class=\"caption\">${esc(note.text)}</p>")
                    append("</figure>\n")
                }
                is TimelineRow.Summary -> {
                    if (!row.slide) {
                        val heading = parseChapterKey(row.chapterKey)?.let { (recId, ms) -> listOfNotNull("Chapter ${row.number}", timeLabel(session, recId, ms)).joinToString(" · ") }
                        append("<h3 class=\"chapter\"><span>${esc(heading.orEmpty())}</span>${esc(row.title.orEmpty())}</h3>\n")
                    }
                    append("<p class=\"sum\">✦ ${esc(row.text)}</p>\n")
                }
                is TimelineRow.Speech -> {
                    if (row.context) continue
                    val rec = session.recordings.indexOfFirst { it.id == row.recId }
                    append("<p class=\"said\" data-rec=\"$rec\" data-ms=\"${row.startMs}\" data-end=\"${row.endMs}\">")
                    append(link(Triple(rec, row.startMs, timeLabel(session, row.recId, row.startMs).orEmpty()), timeLabel(session, row.recId, row.startMs).orEmpty()))
                    if (row.voice != null && row.voice != lastVoice) {
                        append("<span class=\"who\" style=\"color:${css(voiceColor(row.voice).value)}\">${esc(Voices.name(session, row.voice))}</span>")
                    }
                    if (row.voice != null) lastVoice = row.voice
                    if (row.words.isEmpty()) {
                        append(esc(row.text))
                    } else {
                        var at = 0
                        for (w in row.words) {
                            append(esc(row.text.substring(at, w.start)))
                            append("<span data-w=\"${w.atMs}\">${esc(row.text.substring(w.start, w.end))}</span>")
                            at = w.end
                        }
                        append(esc(row.text.substring(at)))
                    }
                    append("</p>\n")
                }
                is TimelineRow.Written -> {
                    val at = moment(session, row.note.recId, row.note.offsetMs)
                    val kind = if (row.note.spoken) "Spoken note" else "Note"
                    append("<div class=\"note\">${link(at, kind + (at?.third?.let { " · $it" } ?: ""))}${esc(row.note.text)}</div>\n")
                }
                is TimelineRow.Mark -> {
                    val at = moment(session, row.note.recId, row.note.offsetMs)
                    append("<div class=\"mark\">${link(at, "★ Marked" + (at?.third?.let { " · $it" } ?: ""))}</div>\n")
                }
                else -> Unit
            }
        }
        append("</main>\n<footer>Made with AudioCool, which records talks and turns them into this, on a phone.</footer>\n")
    }

    /** A recording's number, a time in it and that time's label; null when it isn't linked to one. */
    private fun moment(session: Session, recId: String?, ms: Long?): Triple<Int, Long, String>? {
        if (recId == null || ms == null) return null
        val rec = session.recordings.indexOfFirst { it.id == recId }.takeIf { it >= 0 } ?: return null
        return Triple(rec, ms, timeLabel(session, recId, ms).orEmpty())
    }

    /** [text] that plays from [at] (with the audio in), or just says it. */
    private fun link(at: Triple<Int, Long, String>?, text: String) =
        if (at == null) "<span class=\"t\">${esc(text)}</span>" else "<a class=\"t\" href=\"#\" data-rec=\"${at.first}\" data-ms=\"${at.second}\">${esc(text)}</a>"

    /** A photo for the page: at most 1280 px across, as JPEG. */
    internal fun photo(file: File): ByteArray? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = minOf(1f, 1280f / maxOf(bitmap.width, bitmap.height))
        val sized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        return ByteArrayOutputStream().use { out ->
            sized.compress(Bitmap.CompressFormat.JPEG, 78, out)
            out.toByteArray()
        }
    }

    private fun esc(s: String) = buildString(s.length) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    /** A Compose color's packed value as #rrggbb. */
    private fun css(value: ULong): String = "#%06x".format(((value shr 32).toLong() and 0xFFFFFF))

    private val STYLE = """
:root{--bg:#fbfaf7;--fg:#1d1b20;--muted:#625d68;--card:#efedf4;--accent:#3f51a3;--lit:#d9dfff;--line:#dedae3}
@media (prefers-color-scheme:dark){:root{--bg:#141318;--fg:#e7e1ea;--muted:#a69faf;--card:#221f27;--accent:#b8c4ff;--lit:#2f3c78;--line:#38343e}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:17px/1.6 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
header,main,section,nav,footer{max-width:720px;margin:0 auto;padding:0 16px}
header{padding-top:28px}
.folder{color:var(--accent);font-weight:600;font-size:14px}
h1{font-size:30px;line-height:1.2;margin:6px 0}
.facts{color:var(--muted)}
.made{color:var(--muted);font-size:13px;margin-top:4px}
.player{position:sticky;top:0;z-index:2;display:flex;gap:12px;align-items:center;max-width:720px;margin:16px auto;padding:10px 16px;background:var(--card);border-radius:0 0 16px 16px}
.player button{border:0;border-radius:999px;background:var(--accent);color:var(--bg);font:inherit;font-weight:600;min-width:44px;height:44px;cursor:pointer}
#speed{background:transparent;color:var(--accent)}
.clock{font-variant-numeric:tabular-nums;color:var(--muted);font-size:14px;white-space:nowrap}
#seek{flex:1;min-width:0;accent-color:var(--accent)}
.summary{margin-top:20px}
.summary h2{font-size:18px}
.summary,.chapters{border-bottom:1px solid var(--line);padding-bottom:12px}
.chapters ol{list-style:none;padding:0;margin:0}
.chapters a{display:flex;gap:12px;padding:6px 0;color:var(--fg);text-decoration:none}
.chapters .t{min-width:56px}
main{padding-bottom:40px}
.t{color:var(--accent);font-size:13px;font-variant-numeric:tabular-nums;text-decoration:none;margin-right:10px}
body:not(.has-audio) .t{color:var(--muted);pointer-events:none}
.said{margin:16px 0;border-radius:12px;transition:background .2s}
.said.playing{background:var(--card);padding:8px 12px;margin:10px -12px}
.said [data-w]{cursor:pointer;border-radius:4px}
.said .now{background:var(--lit)}
.who{display:block;font-weight:600;font-size:14px}
.slide{margin:28px 0 12px}
.slide figcaption b{display:block;font-size:20px;margin:2px 0 8px}
.slide img{width:100%;border-radius:14px;display:block}
.caption{color:var(--muted)}
.chapter{margin:28px 0 6px;font-size:20px}
.chapter span{display:block;color:var(--accent);font-size:13px;font-weight:600}
.sum{background:var(--card);border-radius:12px;padding:10px 14px;font-size:15px}
.note,.mark{border-left:3px solid var(--accent);padding:4px 0 4px 12px;margin:10px 0}
.part{font-size:16px;color:var(--muted);border-top:1px solid var(--line);padding-top:16px}
footer{color:var(--muted);font-size:13px;padding-bottom:32px}
"""

    private val SCRIPT = """
(function(){
var audios=[];
document.querySelectorAll('script[id^="audio-"]').forEach(function(s){
  var bin=atob(s.textContent.replace(/\s+/g,'')),bytes=new Uint8Array(bin.length);
  for(var k=0;k<bin.length;k++)bytes[k]=bin.charCodeAt(k);
  var a=new Audio(URL.createObjectURL(new Blob([bytes],{type:s.dataset.mime})));
  a.preload='metadata';audios[+s.id.slice(6)]=a;s.textContent='';
});
if(!audios.length)return;
document.body.classList.add('has-audio');
var player=document.getElementById('player'),playBtn=document.getElementById('play'),speedBtn=document.getElementById('speed'),
    seek=document.getElementById('seek'),time=document.getElementById('time'),dur=document.getElementById('dur');
player.hidden=false;
var cur=audios.findIndex(function(a){return a}),rate=1,rates=[1,1.25,1.5,2,0.75];
var paras=[].slice.call(document.querySelectorAll('.said')),lastPara=null,lastWord=null;
function fmt(s){s=Math.max(0,Math.floor(s||0));var h=Math.floor(s/3600),m=Math.floor(s%3600/60),x=s%60,p=function(n){return(n<10?'0':'')+n};return h?h+':'+p(m)+':'+p(x):p(m)+':'+p(x)}
function play(rec,ms){if(!audios[rec])return;if(rec!==cur){audios[cur].pause();cur=rec}var a=audios[cur];a.playbackRate=rate;a.currentTime=Math.max(0,ms/1000-0.3);a.play()}
document.addEventListener('click',function(e){
  var w=e.target.closest('[data-w]');
  if(w){var p=w.closest('.said');play(+p.dataset.rec,+w.dataset.w);return}
  var t=e.target.closest('[data-rec][data-ms]');
  if(t&&t.tagName==='A'){e.preventDefault();play(+t.dataset.rec,+t.dataset.ms)}
});
function tick(){
  var a=audios[cur],ms=a.currentTime*1000;
  time.textContent=fmt(a.currentTime);
  if(a.duration&&isFinite(a.duration)){dur.textContent=fmt(a.duration);seek.value=Math.round(a.currentTime/a.duration*1000)}
  var p=null;
  for(var i=0;i<paras.length;i++){var q=paras[i];if(+q.dataset.rec===cur&&ms>=+q.dataset.ms-300&&ms<=+q.dataset.end+1500){p=q}}
  if(p!==lastPara){if(lastPara)lastPara.classList.remove('playing');if(p)p.classList.add('playing');lastPara=p}
  var w=null;
  if(p){var ws=p.querySelectorAll('[data-w]');for(var j=0;j<ws.length;j++){if(+ws[j].dataset.w<=ms+80)w=ws[j];else break}}
  if(w!==lastWord){if(lastWord)lastWord.classList.remove('now');if(w)w.classList.add('now');lastWord=w}
  if(!a.paused)setTimeout(tick,100);
}
audios.forEach(function(a){
  a.addEventListener('play',function(){playBtn.textContent='❚❚';playBtn.setAttribute('aria-label','Pause');tick()});
  a.addEventListener('pause',function(){playBtn.textContent='▶';playBtn.setAttribute('aria-label','Play');tick()});
  a.addEventListener('loadedmetadata',tick);
});
playBtn.onclick=function(){var a=audios[cur];if(a.paused)a.play();else a.pause()};
speedBtn.onclick=function(){rate=rates[(rates.indexOf(rate)+1)%rates.length];audios[cur].playbackRate=rate;speedBtn.textContent=rate+'×'};
seek.oninput=function(){var a=audios[cur];if(a.duration&&isFinite(a.duration))a.currentTime=seek.value/1000*a.duration;tick()};
})();
"""
}
