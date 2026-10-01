package com.kjwindham.audiocool.ui

import com.kjwindham.audiocool.audio.PlayerController
import com.kjwindham.audiocool.audio.RecorderController
import com.kjwindham.audiocool.data.SessionRepository

/** Deletes a session, first stopping anything that's recording into or playing from it. */
fun deleteSession(id: String) {
    if (RecorderController.state.value.sessionId == id) RecorderController.stop()
    if (PlayerController.state.value.sessionId == id) PlayerController.release()
    SessionRepository.delete(id)
}
