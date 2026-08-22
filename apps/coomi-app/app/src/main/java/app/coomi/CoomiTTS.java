package app.coomi;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.os.Bundle;
import android.os.Build;

import com.termux.shared.logger.Logger;

import java.util.HashMap;
import java.util.Locale;

/**
 * Thin wrapper around Android TextToSpeech for Coomi voice broadcast.
 *
 * Usage:
 *   CoomiTTS tts = new CoomiTTS(context);
 *   tts.speak("你好，世界");
 *   tts.stop();
 *   tts.shutdown();
 */
public class CoomiTTS {

    private static final String LOG_TAG = "CoomiTTS";
    private final Context mContext;
    private TextToSpeech mTts;
    private boolean mReady;
    private String mPendingText;
    private float mRate = 1.0f;
    private float mPitch = 1.0f;

    public CoomiTTS(Context context) {
        mContext = context.getApplicationContext();
        mTts = new TextToSpeech(mContext, status -> {
            if (status == TextToSpeech.SUCCESS) {
                mReady = true;
                applyLocale();
                if (mPendingText != null) {
                    speak(mPendingText);
                    mPendingText = null;
                }
            } else {
                Logger.logError(LOG_TAG, "TTS init failed: " + status);
            }
        });
        mTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) {}
            @Override public void onError(String id) {
                Logger.logWarn(LOG_TAG, "TTS utterance error: " + id);
            }
        });
    }

    private void applyLocale() {
        // Prefer Chinese if available, fall back to default.
        Locale locale = Locale.CHINESE;
        int result = mTts.setLanguage(locale);
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            locale = Locale.getDefault();
            mTts.setLanguage(locale);
        }
        mTts.setSpeechRate(mRate);
        mTts.setPitch(mPitch);
    }

    public void speak(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (!mReady) {
            mPendingText = text;
            return;
        }
        // Stop any current speech before starting new.
        mTts.stop();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
            mTts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "coomi_" + System.currentTimeMillis());
        } else {
            HashMap<String, String> params = new HashMap<>();
            params.put(TextToSpeech.Engine.KEY_PARAM_VOLUME, "1.0");
            mTts.speak(text, TextToSpeech.QUEUE_FLUSH, params);
        }
    }

    public void stop() {
        if (mReady) mTts.stop();
    }

    public void setRate(float rate) {
        mRate = rate;
        if (mReady) mTts.setSpeechRate(rate);
    }

    public float getRate() {
        return mRate;
    }

    public boolean isReady() {
        return mReady;
    }

    public String getVoicesJson() {
        if (!mReady) return "[]";
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (android.speech.tts.Voice v : mTts.getVoices()) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("name", v.getName());
                o.put("locale", v.getLocale().toString());
                o.put("network", v.isNetworkConnectionRequired());
                arr.put(o);
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    public boolean setVoice(String voiceName) {
        if (!mReady) return false;
        for (android.speech.tts.Voice v : mTts.getVoices()) {
            if (v.getName().equals(voiceName)) {
                mTts.setVoice(v);
                return true;
            }
        }
        return false;
    }

    public void setPitch(float pitch) {
        mPitch = pitch;
        if (mReady) mTts.setPitch(pitch);
    }

    public float getPitch() {
        return mPitch;
    }

    public void shutdown() {
        if (mTts != null) {
            mTts.stop();
            mTts.shutdown();
            mTts = null;
        }
        mReady = false;
    }
}
