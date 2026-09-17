// Package ytaudio exposes YouTube audio extraction via gomobile bindings.
// gomobile bind -target=android/arm64 -o ytaudio.aar ./ytaudio
package ytaudio

import (
	"fmt"

	youtube "github.com/kkdai/youtube/v2"
)

type youtubeClient interface {
	GetVideo(url string) (*youtube.Video, error)
	GetStreamURL(video *youtube.Video, format *youtube.Format) (string, error)
}

// ExtractAudioURL returns a direct streamable audio URL for a YouTube video.
// The URL is time-limited by YouTube; pass it to ExoPlayer immediately.
func ExtractAudioURL(videoURL string) (string, error) {
	return extractAudioURL(&youtube.Client{}, videoURL)
}

// PlaybackUserAgent returns the User-Agent that must be sent when directly requesting a
// URL returned by ExtractAudioURL. googlevideo's CDN ties a signed stream URL to the
// player client identity that requested it (visible in the URL's own "c=" parameter,
// e.g. "c=ANDROID_VR") and 403s requests from a mismatched client — this is
// kkdai/youtube's youtube.DefaultClient, which ExtractAudioURL implicitly uses and which
// has changed identity across library versions before.
func PlaybackUserAgent() string {
	return youtube.DefaultClient.UserAgent
}

func extractAudioURL(client youtubeClient, videoURL string) (string, error) {
	video, err := client.GetVideo(videoURL)
	if err != nil {
		return "", fmt.Errorf("get video: %w", err)
	}

	formats := video.Formats.WithAudioChannels()
	if len(formats) == 0 {
		return "", fmt.Errorf("no audio formats available")
	}

	// Prefer audio-only streams (Width == 0 means no video track)
	for i := range formats {
		f := &formats[i]
		if f.Width == 0 {
			url, err := client.GetStreamURL(video, f)
			if err != nil {
				continue
			}
			return url, nil
		}
	}

	// Fallback: first format that has audio
	url, err := client.GetStreamURL(video, &formats[0])
	if err != nil {
		return "", fmt.Errorf("get stream url: %w", err)
	}
	return url, nil
}
