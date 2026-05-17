package ytaudio

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
)

// ---- fake HTTP client ----

type fakeHTTPClient struct {
	statusCode int
	body       string
	err        error
}

func (f *fakeHTTPClient) Do(_ *http.Request) (*http.Response, error) {
	if f.err != nil {
		return nil, f.err
	}
	return &http.Response{
		StatusCode: f.statusCode,
		Body:       io.NopCloser(strings.NewReader(f.body)),
	}, nil
}

// ---- response builder helpers ----

type fakeVideo struct {
	id       string
	title    string
	channel  string
	duration string // "3:45", "" for live
}

func buildInnertubeResponse(videos []fakeVideo) string {
	type run struct {
		Text string `json:"text"`
	}
	type runs struct {
		Runs []run `json:"runs"`
	}
	type lengthText struct {
		SimpleText string `json:"simpleText"`
	}
	type renderer struct {
		VideoId    string     `json:"videoId"`
		Title      runs       `json:"title"`
		OwnerText  runs       `json:"ownerText"`
		LengthText lengthText `json:"lengthText"`
	}
	type itemContent struct {
		VideoRenderer *renderer `json:"videoRenderer,omitempty"`
	}
	type itemSection struct {
		Contents []itemContent `json:"contents"`
	}
	type itemSectionWrapper struct {
		ItemSectionRenderer *itemSection `json:"itemSectionRenderer,omitempty"`
	}
	type sectionList struct {
		Contents []itemSectionWrapper `json:"contents"`
	}
	type primaryContents struct {
		SectionListRenderer sectionList `json:"sectionListRenderer"`
	}
	type twoCol struct {
		PrimaryContents primaryContents `json:"primaryContents"`
	}
	type contents struct {
		TwoColumnSearchResultsRenderer twoCol `json:"twoColumnSearchResultsRenderer"`
	}
	type response struct {
		Contents contents `json:"contents"`
	}

	items := make([]itemContent, len(videos))
	for i, v := range videos {
		items[i] = itemContent{
			VideoRenderer: &renderer{
				VideoId:    v.id,
				Title:      runs{Runs: []run{{Text: v.title}}},
				OwnerText:  runs{Runs: []run{{Text: v.channel}}},
				LengthText: lengthText{SimpleText: v.duration},
			},
		}
	}

	r := response{Contents: contents{TwoColumnSearchResultsRenderer: twoCol{
		PrimaryContents: primaryContents{SectionListRenderer: sectionList{
			Contents: []itemSectionWrapper{{ItemSectionRenderer: &itemSection{Contents: items}}},
		}},
	}}}
	b, _ := json.Marshal(r)
	return string(b)
}

func buildNonVideoResponse() string {
	// A section with a non-video item (no videoRenderer) followed by a real video.
	type shelfRenderer struct{ Dummy string `json:"dummy"` }
	type itemContent struct {
		ShelfRenderer *shelfRenderer `json:"shelfRenderer,omitempty"`
		VideoRenderer *videoRendererData `json:"videoRenderer,omitempty"`
	}
	type itemSection struct{ Contents []itemContent `json:"contents"` }
	type wrapper struct{ ItemSectionRenderer *itemSection `json:"itemSectionRenderer,omitempty"` }
	type sl struct{ Contents []wrapper `json:"contents"` }
	type pc struct{ SectionListRenderer sl `json:"sectionListRenderer"` }
	type tc struct{ PrimaryContents pc `json:"primaryContents"` }
	type ct struct{ TwoColumnSearchResultsRenderer tc `json:"twoColumnSearchResultsRenderer"` }
	type resp struct{ Contents ct `json:"contents"` }

	r := resp{Contents: ct{TwoColumnSearchResultsRenderer: tc{PrimaryContents: pc{SectionListRenderer: sl{
		Contents: []wrapper{{ItemSectionRenderer: &itemSection{Contents: []itemContent{
			{ShelfRenderer: &shelfRenderer{Dummy: "ad"}},
			{VideoRenderer: &videoRendererData{
				VideoId: "abc123",
				Title:   struct {
					Runs []struct {
						Text string `json:"text"`
					} `json:"runs"`
				}{Runs: []struct {
					Text string `json:"text"`
				}{{Text: "Real Video"}}},
			}},
		}}}},
	}}}}}
	b, _ := json.Marshal(r)
	return string(b)
}

// ---- tests ----

func TestSearchVideos_HTTPError(t *testing.T) {
	client := &fakeHTTPClient{err: errors.New("connection refused")}
	_, err := searchVideos(client, "lofi", 5)
	if err == nil || !strings.Contains(err.Error(), "search request") {
		t.Fatalf("expected 'search request' error, got %v", err)
	}
}

func TestSearchVideos_Non200Status(t *testing.T) {
	client := &fakeHTTPClient{statusCode: 429, body: "rate limited"}
	_, err := searchVideos(client, "lofi", 5)
	if err == nil || !strings.Contains(err.Error(), "429") {
		t.Fatalf("expected status 429 error, got %v", err)
	}
}

func TestSearchVideos_InvalidJSON(t *testing.T) {
	client := &fakeHTTPClient{statusCode: 200, body: "not json {{{"}
	_, err := searchVideos(client, "lofi", 5)
	if err == nil || !strings.Contains(err.Error(), "parse response") {
		t.Fatalf("expected 'parse response' error, got %v", err)
	}
}

func TestSearchVideos_ReturnsResults(t *testing.T) {
	body := buildInnertubeResponse([]fakeVideo{
		{id: "aaa", title: "Lofi Beats", channel: "Lofi Girl", duration: "3:45"},
		{id: "bbb", title: "Piano Music", channel: "Relaxing", duration: "1:02:10"},
	})
	client := &fakeHTTPClient{statusCode: 200, body: body}

	list, err := searchVideos(client, "lofi", 10)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if list.Len() != 2 {
		t.Fatalf("want 2 results, got %d", list.Len())
	}

	r0 := list.Get(0)
	if r0.Id != "aaa" || r0.Title != "Lofi Beats" || r0.Channel != "Lofi Girl" || r0.DurationSecs != 225 {
		t.Fatalf("unexpected result[0]: %+v", r0)
	}
	r1 := list.Get(1)
	if r1.Id != "bbb" || r1.DurationSecs != 3730 {
		t.Fatalf("unexpected result[1]: %+v", r1)
	}
}

func TestSearchVideos_RespectsMaxResults(t *testing.T) {
	body := buildInnertubeResponse([]fakeVideo{
		{id: "a", title: "A", channel: "Ch", duration: "1:00"},
		{id: "b", title: "B", channel: "Ch", duration: "2:00"},
		{id: "c", title: "C", channel: "Ch", duration: "3:00"},
	})
	client := &fakeHTTPClient{statusCode: 200, body: body}

	list, err := searchVideos(client, "music", 2)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if list.Len() != 2 {
		t.Fatalf("want 2 results (maxResults), got %d", list.Len())
	}
}

func TestSearchVideos_SkipsNonVideoItems(t *testing.T) {
	client := &fakeHTTPClient{statusCode: 200, body: buildNonVideoResponse()}
	list, err := searchVideos(client, "music", 10)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if list.Len() != 1 {
		t.Fatalf("want 1 video result (shelf skipped), got %d", list.Len())
	}
	if list.Get(0).Id != "abc123" {
		t.Fatalf("unexpected id: %s", list.Get(0).Id)
	}
}

func TestSearchVideos_LiveStreamHasZeroDuration(t *testing.T) {
	body := buildInnertubeResponse([]fakeVideo{
		{id: "live1", title: "Live Stream", channel: "Ch", duration: ""},
	})
	client := &fakeHTTPClient{statusCode: 200, body: body}

	list, err := searchVideos(client, "live", 5)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if list.Len() != 1 {
		t.Fatalf("want 1 result, got %d", list.Len())
	}
	if list.Get(0).DurationSecs != 0 {
		t.Fatalf("want duration 0 for live stream, got %d", list.Get(0).DurationSecs)
	}
}

func TestSearchVideos_EmptyResponse(t *testing.T) {
	body := buildInnertubeResponse([]fakeVideo{})
	client := &fakeHTTPClient{statusCode: 200, body: body}

	list, err := searchVideos(client, "xyzzy_no_results", 5)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if list.Len() != 0 {
		t.Fatalf("want 0 results, got %d", list.Len())
	}
}

// ---- searchVideosJSON tests ----

func TestSearchVideosJSON_ReturnsJSONArray(t *testing.T) {
	body := buildInnertubeResponse([]fakeVideo{
		{id: "aaa", title: "Lofi Beats", channel: "Lofi Girl", duration: "3:45"},
	})
	client := &fakeHTTPClient{statusCode: 200, body: body}

	got, err := searchVideosJSON(client, "lofi", 10)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	var results []struct {
		Id           string `json:"id"`
		Title        string `json:"title"`
		Channel      string `json:"channel"`
		DurationSecs int64  `json:"durationSecs"`
	}
	if err := json.Unmarshal([]byte(got), &results); err != nil {
		t.Fatalf("invalid JSON: %v\n%s", err, got)
	}
	if len(results) != 1 {
		t.Fatalf("want 1 result, got %d", len(results))
	}
	if results[0].Id != "aaa" || results[0].Title != "Lofi Beats" || results[0].Channel != "Lofi Girl" || results[0].DurationSecs != 225 {
		t.Fatalf("unexpected result: %+v", results[0])
	}
}

func TestSearchVideosJSON_PropagatesHTTPError(t *testing.T) {
	client := &fakeHTTPClient{err: errors.New("network error")}
	_, err := searchVideosJSON(client, "lofi", 5)
	if err == nil {
		t.Fatal("expected error, got nil")
	}
}

func TestSearchVideosJSON_EmptyResultsIsEmptyArray(t *testing.T) {
	body := buildInnertubeResponse([]fakeVideo{})
	client := &fakeHTTPClient{statusCode: 200, body: body}

	got, err := searchVideosJSON(client, "xyzzy", 5)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != "[]" && got != "null" {
		// json.Marshal of an empty slice returns "[]", nil slice returns "null"
		// both are acceptable empty states
		var arr []interface{}
		if e := json.Unmarshal([]byte(got), &arr); e != nil || len(arr) != 0 {
			t.Fatalf("expected empty JSON array, got: %s", got)
		}
	}
}

// ---- parseDuration unit tests ----

func TestParseDuration(t *testing.T) {
	cases := []struct {
		in   string
		want int64
	}{
		{"3:45", 225},
		{"1:02:10", 3730},
		{"0:30", 30},
		{"", 0},
		{"LIVE", 0},
	}
	for _, c := range cases {
		got := parseDuration(c.in)
		if got != c.want {
			t.Errorf("parseDuration(%q) = %d, want %d", c.in, got, c.want)
		}
	}
}
