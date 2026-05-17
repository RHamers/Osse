package ytaudio

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
)

// innertubeSearchURL is YouTube's internal API used by the web client.
// The key is YouTube's own public WEB client key — not a user credential.
const innertubeSearchURL = "https://www.youtube.com/youtubei/v1/search?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

type httpDoer interface {
	Do(req *http.Request) (*http.Response, error)
}

// VideoResult holds metadata for a single YouTube video search result.
type VideoResult struct {
	Id           string `json:"id"`
	Title        string `json:"title"`
	Channel      string `json:"channel"`
	DurationSecs int64  `json:"durationSecs"`
}

// VideoResultList is a gomobile-compatible wrapper around a slice of VideoResult.
// Use Len() and Get(i) to iterate from Kotlin.
type VideoResultList struct {
	items []*VideoResult
}

func (l *VideoResultList) Len() int               { return len(l.items) }
func (l *VideoResultList) Get(i int) *VideoResult { return l.items[i] }

// SearchVideos queries YouTube for videos matching query and returns up to maxResults results.
func SearchVideos(query string, maxResults int) (*VideoResultList, error) {
	return searchVideos(&http.Client{}, query, maxResults)
}

// SearchVideosJSON queries YouTube and returns results as a JSON array.
// Use this from Kotlin/JNI instead of SearchVideos — plain strings cross
// the JNI boundary without the gomobile struct-wrapper machinery.
func SearchVideosJSON(query string, maxResults int) (string, error) {
	return searchVideosJSON(&http.Client{}, query, maxResults)
}

func searchVideosJSON(client httpDoer, query string, maxResults int) (string, error) {
	list, err := searchVideos(client, query, maxResults)
	if err != nil {
		return "", err
	}
	data, err := json.Marshal(list.items)
	if err != nil {
		return "", fmt.Errorf("marshal results: %w", err)
	}
	return string(data), nil
}

func searchVideos(client httpDoer, query string, maxResults int) (*VideoResultList, error) {
	body, err := buildSearchRequestBody(query)
	if err != nil {
		return nil, fmt.Errorf("build request: %w", err)
	}

	req, err := http.NewRequest(http.MethodPost, innertubeSearchURL, bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "Mozilla/5.0")
	req.Header.Set("Accept-Language", "en-US,en;q=0.9")

	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("search request: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("search returned status %d", resp.StatusCode)
	}

	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("read response: %w", err)
	}

	return parseSearchResponse(data, maxResults)
}

func buildSearchRequestBody(query string) ([]byte, error) {
	type clientCtx struct {
		ClientName    string `json:"clientName"`
		ClientVersion string `json:"clientVersion"`
		Hl            string `json:"hl"`
		Gl            string `json:"gl"`
	}
	type context struct {
		Client clientCtx `json:"client"`
	}
	type searchRequest struct {
		Context context `json:"context"`
		Query   string  `json:"query"`
		Params  string  `json:"params"` // EgIQAQ== = video-only filter
	}
	return json.Marshal(searchRequest{
		Context: context{Client: clientCtx{
			ClientName:    "WEB",
			ClientVersion: "2.20210721.00.00",
			Hl:            "en",
			Gl:            "US",
		}},
		Query:  query,
		Params: "EgIQAQ==",
	})
}

// ---- minimal structs to walk the innertube search response ----

type innertubeSearchResponse struct {
	Contents struct {
		TwoColumnSearchResultsRenderer struct {
			PrimaryContents struct {
				SectionListRenderer struct {
					Contents []sectionContent `json:"contents"`
				} `json:"sectionListRenderer"`
			} `json:"primaryContents"`
		} `json:"twoColumnSearchResultsRenderer"`
	} `json:"contents"`
}

type sectionContent struct {
	ItemSectionRenderer *struct {
		Contents []itemContent `json:"contents"`
	} `json:"itemSectionRenderer"`
}

type itemContent struct {
	VideoRenderer *videoRendererData `json:"videoRenderer"`
}

type videoRendererData struct {
	VideoId   string `json:"videoId"`
	Title     struct {
		Runs []struct {
			Text string `json:"text"`
		} `json:"runs"`
	} `json:"title"`
	OwnerText struct {
		Runs []struct {
			Text string `json:"text"`
		} `json:"runs"`
	} `json:"ownerText"`
	LengthText struct {
		SimpleText string `json:"simpleText"`
	} `json:"lengthText"`
}

func parseSearchResponse(data []byte, maxResults int) (*VideoResultList, error) {
	var resp innertubeSearchResponse
	if err := json.Unmarshal(data, &resp); err != nil {
		return nil, fmt.Errorf("parse response: %w", err)
	}

	list := &VideoResultList{}
	sections := resp.Contents.TwoColumnSearchResultsRenderer.PrimaryContents.SectionListRenderer.Contents
	for _, section := range sections {
		if section.ItemSectionRenderer == nil {
			continue
		}
		for _, item := range section.ItemSectionRenderer.Contents {
			vr := item.VideoRenderer
			if vr == nil || vr.VideoId == "" {
				continue
			}
			title := ""
			if len(vr.Title.Runs) > 0 {
				title = vr.Title.Runs[0].Text
			}
			channel := ""
			if len(vr.OwnerText.Runs) > 0 {
				channel = vr.OwnerText.Runs[0].Text
			}
			list.items = append(list.items, &VideoResult{
				Id:           vr.VideoId,
				Title:        title,
				Channel:      channel,
				DurationSecs: parseDuration(vr.LengthText.SimpleText),
			})
			if len(list.items) >= maxResults {
				return list, nil
			}
		}
	}
	return list, nil
}

// parseDuration converts "3:45" or "1:23:45" to seconds.
// Returns 0 for live streams or unparseable values.
func parseDuration(s string) int64 {
	if s == "" {
		return 0
	}
	parts := strings.Split(s, ":")
	var total int64
	for _, p := range parts {
		n, err := strconv.ParseInt(strings.TrimSpace(p), 10, 64)
		if err != nil {
			return 0
		}
		total = total*60 + n
	}
	return total
}
