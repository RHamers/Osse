// Package ytaudio exposes YouTube audio extraction via gomobile bindings.
// gomobile bind -target=android/arm64 -o ytaudio.aar ./ytaudio
package ytaudio

import (
	"fmt"
	"io"
	"os"

	youtube "github.com/kkdai/youtube/v2"
)

// ExtractAudioURL returns a direct streamable audio URL for a YouTube video.
// The URL is time-limited by YouTube; pass it to ExoPlayer immediately.
func ExtractAudioURL(videoURL string) (string, error) {
	client := youtube.Client{}
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

// DownloadAudio downloads the raw audio stream to outputPath.
// The file format is whatever YouTube serves (typically webm or m4a).
// Pass the file through ffmpeg-kit on the Android side to convert to MP3.
func DownloadAudio(videoURL string, outputPath string) error {
	client := youtube.Client{}
	video, err := client.GetVideo(videoURL)
	if err != nil {
		return fmt.Errorf("get video: %w", err)
	}

	formats := video.Formats.WithAudioChannels()
	if len(formats) == 0 {
		return fmt.Errorf("no audio formats available")
	}

	var format *youtube.Format
	for i := range formats {
		if formats[i].Width == 0 {
			format = &formats[i]
			break
		}
	}
	if format == nil {
		format = &formats[0]
	}

	stream, _, err := client.GetStream(video, format)
	if err != nil {
		return fmt.Errorf("get stream: %w", err)
	}
	defer stream.Close()

	out, err := os.Create(outputPath)
	if err != nil {
		return fmt.Errorf("create output file: %w", err)
	}
	defer out.Close()

	if _, err = io.Copy(out, stream); err != nil {
		return fmt.Errorf("write audio: %w", err)
	}
	return nil
}
