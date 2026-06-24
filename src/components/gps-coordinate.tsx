import { useCallback, useEffect, useRef, useState } from 'react'
import { Copy, RefreshCw, MapPin, AlertCircle, Check } from 'lucide-react'
import { Button } from './ui/button'

type Status = 'idle' | 'loading' | 'ready' | 'error'

export function GpsCoordinate() {
  const [coord, setCoord] = useState('')
  const [status, setStatus] = useState<Status>('idle')
  const [message, setMessage] = useState('')
  const [copied, setCopied] = useState(false)
  const [accuracy, setAccuracy] = useState<number | null>(null)
  const watchId = useRef<number | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const fetchLocation = useCallback(() => {
    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      setStatus('error')
      setMessage('Is device par GPS support nahi hai.')
      return
    }

    // Clear any in-progress lock-on so Refresh always starts fresh.
    if (watchId.current !== null) navigator.geolocation.clearWatch(watchId.current)
    if (timer.current) clearTimeout(timer.current)

    setStatus('loading')
    setMessage('')
    setAccuracy(null)

    // Accuracy strategy: the first fixes a browser returns are usually coarse
    // WiFi/network fixes (accuracy in hundreds/thousands of meters). The true
    // GPS lock arrives a few seconds later with much better accuracy. So we
    // keep watching, show the best fix live as it converges, and only stop
    // once we get a real GPS-grade lock (<= 15 m) or the time window ends.
    let best: GeolocationPosition | null = null
    const GOOD_ENOUGH = 15 // meters — a real GPS lock
    const MAX_WAIT = 30000 // ms — give GPS enough time to lock

    const apply = (pos: GeolocationPosition) => {
      const lat = pos.coords.latitude.toFixed(4)
      const lng = pos.coords.longitude.toFixed(4)
      setCoord(`${lat},${lng}`)
      setAccuracy(Math.round(pos.coords.accuracy))
      setStatus('ready')
    }

    const finish = () => {
      if (watchId.current !== null) {
        navigator.geolocation.clearWatch(watchId.current)
        watchId.current = null
      }
      if (timer.current) {
        clearTimeout(timer.current)
        timer.current = null
      }
      if (best) {
        apply(best)
        // Warn if we never got a proper GPS lock (likely no real GPS / indoors).
        if (best.coords.accuracy > 50) {
          setMessage('GPS lock weak hai — khule asmaan ke niche jaakar Refresh dabao.')
        }
      } else {
        setStatus('error')
        setMessage('Location nahi mili. GPS ON karte dobara try karo.')
      }
    }

    watchId.current = navigator.geolocation.watchPosition(
      (pos) => {
        // Keep the most accurate fix seen so far and show it live.
        if (!best || pos.coords.accuracy < best.coords.accuracy) {
          best = pos
          apply(pos)
        }
        if (best.coords.accuracy <= GOOD_ENOUGH) {
          finish()
        }
      },
      (err) => {
        if (watchId.current !== null) {
          navigator.geolocation.clearWatch(watchId.current)
          watchId.current = null
        }
        if (timer.current) {
          clearTimeout(timer.current)
          timer.current = null
        }
        setStatus('error')
        if (err.code === err.PERMISSION_DENIED) {
          setMessage('Location permission do aur GPS ON karo, phir Refresh dabao.')
        } else if (err.code === err.POSITION_UNAVAILABLE) {
          setMessage('GPS OFF hai. Kripya GPS ON karte Refresh dabao.')
        } else {
          setMessage('Location nahi mili. GPS ON karte dobara try karo.')
        }
      },
      { enableHighAccuracy: true, timeout: MAX_WAIT, maximumAge: 0 },
    )

    // Stop after the window and use the best fix collected so far.
    timer.current = setTimeout(finish, MAX_WAIT)
  }, [])

  // Fetch once when the app opens; clean up any watcher/timer on unmount.
  useEffect(() => {
    fetchLocation()
    return () => {
      if (watchId.current !== null && navigator.geolocation) {
        navigator.geolocation.clearWatch(watchId.current)
      }
      if (timer.current) clearTimeout(timer.current)
    }
  }, [fetchLocation])

  const handleCopy = useCallback(async () => {
    if (!coord) return
    try {
      await navigator.clipboard.writeText(coord)
    } catch {
      // Fallback for older browsers.
      const ta = document.createElement('textarea')
      ta.value = coord
      ta.style.position = 'fixed'
      ta.style.left = '-9999px'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    // Light vibration feedback.
    if (typeof navigator !== 'undefined' && navigator.vibrate) {
      navigator.vibrate(40)
    }
    setCopied(true)
    setTimeout(() => setCopied(false), 1500)
  }, [coord])

  return (
    <div className="sticky top-0 z-30 bg-primary text-primary-foreground shadow-md">
      <div className="mx-auto flex max-w-6xl items-center justify-between gap-3 px-4 py-3">
        <div className="flex min-w-0 flex-1 items-center gap-3">
          <span className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-primary-foreground/15 ring-1 ring-primary-foreground/20">
            <MapPin className="size-5" />
          </span>
          {status === 'ready' ? (
            <span className="flex min-w-0 flex-col">
              <span className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-[0.16em] text-primary-foreground/70">
                <span className="inline-block size-1.5 animate-pulse rounded-full bg-primary-foreground" />
                Live location
              </span>
              <span className="truncate font-mono text-lg font-bold tabular-nums sm:text-xl">
                {coord}
              </span>
              {accuracy !== null && (
                <span className="text-[11px] text-primary-foreground/70">±{accuracy} m accuracy</span>
              )}
            </span>
          ) : status === 'loading' ? (
            <span className="flex items-center gap-2 text-sm text-primary-foreground/80">
              <RefreshCw className="size-4 animate-spin" />
              Accurate location le rahe hain...
            </span>
          ) : status === 'error' ? (
            <span className="flex items-center gap-1.5 text-sm text-primary-foreground">
              <AlertCircle className="size-4 shrink-0" />
              {message}
            </span>
          ) : (
            <span className="text-sm text-primary-foreground/80">--</span>
          )}
        </div>

        <div className="flex shrink-0 items-center gap-2">
          <Button
            type="button"
            size="icon"
            variant="secondary"
            onClick={fetchLocation}
            disabled={status === 'loading'}
            aria-label="Refresh location"
            className="bg-primary-foreground/15 text-primary-foreground hover:bg-primary-foreground/25"
          >
            <RefreshCw className={`size-4 ${status === 'loading' ? 'animate-spin' : ''}`} />
          </Button>
          <Button
            type="button"
            variant="secondary"
            onClick={handleCopy}
            disabled={status !== 'ready'}
            aria-label="Copy coordinate"
            className="bg-primary-foreground text-primary hover:bg-primary-foreground/90"
          >
            {copied ? <Check className="size-4" /> : <Copy className="size-4" />}
            <span className="ml-1">{copied ? 'Copied' : 'Copy'}</span>
          </Button>
        </div>
      </div>
    </div>
  )
}
