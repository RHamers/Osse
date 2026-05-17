//go:build tools

package ytaudio

// Keep golang.org/x/mobile in the module graph so gobind can locate it.
import _ "golang.org/x/mobile/bind"
