package app.coomi;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.os.Handler;
import android.os.Looper;

import com.termux.shared.logger.Logger;

import java.util.List;

/**
 * Speech-to-text using Android SpeechRecognizer.
 * 
 * Usage:
 *   CoomiSTT stt = new CoomiSTT(activity);
 *   stt.start(listener);  // listener receives onResult(String) or onError(String)
 *   stt.stop();
 */
public class CoomiSTT {

    private static final String LOG_TAG = "CoomiSTT";
    private static final int REQUEST_CODE = 9001;

    public interface SttListener {
        void onResult(String text);
        void onError(String error);
        void onPartial(String text);
    }

    private final Context mContext;
    private SpeechRecognizer mRecognizer;
    private SttListener mListener;
    private boolean mListening;

    public CoomiSTT(Activity activity) {
        mContext = activity.getApplicationContext();
    }

    public boolean isAvailable() {
        return SpeechRecognizer.isRecognitionAvailable(mContext);
    }

    public void start(SttListener listener) {
        if (mListening) return;
        mListener = listener;
        mListening = true;

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);

        mRecognizer = SpeechRecognizer.createSpeechRecognizer(mContext);
        mRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onPartialResults(Bundle partialResults) {
                List<String> results = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (results != null && !results.isEmpty() && mListener != null) {
                    new Handler(Looper.getMainLooper()).post(() -> mListener.onPartial(results.get(0)));
                }
            }
            @Override public void onResults(Bundle results) {
                mListening = false;
                List<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty() && mListener != null) {
                    new Handler(Looper.getMainLooper()).post(() -> mListener.onResult(matches.get(0)));
                } else if (mListener != null) {
                    new Handler(Looper.getMainLooper()).post(() -> mListener.onError("无识别结果"));
                }
            }
            @Override public void onError(int error) {
                mListening = false;
                String msg;
                switch (error) {
                    case SpeechRecognizer.ERROR_AUDIO: msg = "录音失败"; break;
                    case SpeechRecognizer.ERROR_CLIENT: msg = "客户端错误"; break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: msg = "需要麦克风权限"; break;
                    case SpeechRecognizer.ERROR_NETWORK: msg = "网络错误"; break;
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: msg = "网络超时"; break;
                    case SpeechRecognizer.ERROR_NO_MATCH: msg = "未识别到语音"; break;
                    case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: msg = "识别器忙"; break;
                    case SpeechRecognizer.ERROR_SERVER: msg = "服务器错误"; break;
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: msg = "未检测到语音"; break;
                    default: msg = "识别错误: " + error; break;
                }
                if (mListener != null) {
                    new Handler(Looper.getMainLooper()).post(() -> mListener.onError(msg));
                }
            }
            @Override public void onEvent(int eventType, Bundle params) {}
        });

        if (mContext instanceof Activity) {
            ((Activity) mContext).startActivityForResult(intent, REQUEST_CODE);
        }
    }

    public void stop() {
        mListening = false;
        if (mRecognizer != null) {
            mRecognizer.stopListening();
            mRecognizer.destroy();
            mRecognizer = null;
        }
    }

    public boolean isListening() {
        return mListening;
    }
}
