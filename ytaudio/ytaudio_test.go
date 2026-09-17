package ytaudio

import (
	"errors"
	"strings"
	"testing"

	youtube "github.com/kkdai/youtube/v2"
)

// fakeClient implements youtubeClient for testing.
// streamURLResults is consumed in order for each GetStreamURL call; if exhausted
// the last entry is reused, so a single-entry slice means "always return this".
type fakeClient struct {
	video            *youtube.Video
	getVideoErr      error
	streamURLResults []streamURLResult
	streamURLCalls   int
}

type streamURLResult struct {
	url string
	err error
}

func (f *fakeClient) GetVideo(_ string) (*youtube.Video, error) {
	return f.video, f.getVideoErr
}

func (f *fakeClient) GetStreamURL(_ *youtube.Video, _ *youtube.Format) (string, error) {
	if len(f.streamURLResults) == 0 {
		return "https://stream.example.com/audio", nil
	}
	i := f.streamURLCalls
	if i >= len(f.streamURLResults) {
		i = len(f.streamURLResults) - 1
	}
	f.streamURLCalls++
	r := f.streamURLResults[i]
	return r.url, r.err
}

// helpers for building test videos

func audioOnlyFormat(itag int) youtube.Format {
	return youtube.Format{ItagNo: itag, AudioChannels: 2, Width: 0}
}

func audioVideoFormat(itag int) youtube.Format {
	return youtube.Format{ItagNo: itag, AudioChannels: 2, Width: 1280}
}

func videoWithFormats(formats ...youtube.Format) *youtube.Video {
	return &youtube.Video{Formats: youtube.FormatList(formats)}
}

// --- extractAudioURL tests ---

func TestExtractAudioURL_GetVideoError(t *testing.T) {
	client := &fakeClient{getVideoErr: errors.New("network error")}
	_, err := extractAudioURL(client, "https://youtube.com/watch?v=abc")
	if err == nil || !strings.Contains(err.Error(), "get video") {
		t.Fatalf("expected 'get video' error, got %v", err)
	}
}

func TestExtractAudioURL_NoAudioFormats(t *testing.T) {
	client := &fakeClient{video: videoWithFormats()}
	_, err := extractAudioURL(client, "https://youtube.com/watch?v=abc")
	if err == nil || !strings.Contains(err.Error(), "no audio formats available") {
		t.Fatalf("expected 'no audio formats available', got %v", err)
	}
}

func TestExtractAudioURL_PrefersAudioOnlyFormat(t *testing.T) {
	const wantURL = "https://stream.example.com/audio-only"
	client := &fakeClient{
		video: videoWithFormats(
			audioVideoFormat(137), // Width>0 — skipped in loop, no GetStreamURL call
			audioOnlyFormat(140),  // Width==0 — chosen, first GetStreamURL call
		),
		streamURLResults: []streamURLResult{
			{url: wantURL},
		},
	}
	got, err := extractAudioURL(client, "https://youtube.com/watch?v=abc")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != wantURL {
		t.Fatalf("want %q, got %q", wantURL, got)
	}
}

func TestExtractAudioURL_FallsBackWhenNoAudioOnly(t *testing.T) {
	const wantURL = "https://stream.example.com/fallback"
	client := &fakeClient{
		video:            videoWithFormats(audioVideoFormat(137)),
		streamURLResults: []streamURLResult{{url: wantURL}},
	}
	got, err := extractAudioURL(client, "https://youtube.com/watch?v=abc")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != wantURL {
		t.Fatalf("want %q, got %q", wantURL, got)
	}
}

func TestExtractAudioURL_AudioOnlyGetStreamURLFails_FallbackSucceeds(t *testing.T) {
	const wantURL = "https://stream.example.com/fallback"
	client := &fakeClient{
		video: videoWithFormats(audioOnlyFormat(140)),
		streamURLResults: []streamURLResult{
			{err: errors.New("cipher error")}, // audio-only call fails
			{url: wantURL},                    // fallback call succeeds
		},
	}
	got, err := extractAudioURL(client, "https://youtube.com/watch?v=abc")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != wantURL {
		t.Fatalf("want %q, got %q", wantURL, got)
	}
}

func TestExtractAudioURL_AllGetStreamURLFail(t *testing.T) {
	client := &fakeClient{
		video:            videoWithFormats(audioOnlyFormat(140)),
		streamURLResults: []streamURLResult{{err: errors.New("cipher error")}},
	}
	_, err := extractAudioURL(client, "https://youtube.com/watch?v=abc")
	if err == nil || !strings.Contains(err.Error(), "get stream url") {
		t.Fatalf("expected 'get stream url' error, got %v", err)
	}
}

// --- PlaybackUserAgent tests ---

func TestPlaybackUserAgent_MatchesDefaultClient(t *testing.T) {
	got := PlaybackUserAgent()
	if got != youtube.DefaultClient.UserAgent {
		t.Fatalf("PlaybackUserAgent() = %q, want youtube.DefaultClient.UserAgent %q", got, youtube.DefaultClient.UserAgent)
	}
	if got == "" {
		t.Fatal("PlaybackUserAgent() must not be empty")
	}
}
