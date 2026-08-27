import { useState, useEffect, useRef, useCallback } from 'react';
import { useQuery } from '@tanstack/react-query';
import { MapContainer, TileLayer, useMap } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { getLiveVehicles, type LiveVehicle } from '../api/tracking';

const DEFAULT_CENTER: L.LatLngExpression = [44.95, 34.11];
const DEFAULT_ZOOM = 12;

// Smooth marker animation CSS (Leaflet positions via transform: translate3d)
const STYLE_ID = 'live-map-smooth-markers';
function ensureSmoothStyle() {
  if (document.getElementById(STYLE_ID)) return;
  const s = document.createElement('style');
  s.id = STYLE_ID;
  s.textContent = `.leaflet-marker-icon.vehicle-marker { transition: transform 4.5s linear !important; }`;
  document.head.appendChild(s);
}

function createVehicleIcon(type: string): L.DivIcon {
  const colors: Record<string, string> = {
    'Автобус': '#2563eb',
    'Троллейбус': '#16a34a',
    'Трамвай': '#dc2626',
    'Маршрутное такси': '#f59e0b',
  };
  const color = colors[type] || '#6b7280';
  return L.divIcon({
    className: 'vehicle-marker',
    html: `<div style="width:14px;height:14px;border-radius:50%;background:${color};border:2px solid white;box-shadow:0 1px 4px rgba(0,0,0,0.5)"></div>`,
    iconSize: [18, 18],
    iconAnchor: [9, 9],
  });
}

const ICON_CACHE: Record<string, L.DivIcon> = {};
function getIcon(type: string): L.DivIcon {
  if (!ICON_CACHE[type]) ICON_CACHE[type] = createVehicleIcon(type);
  return ICON_CACHE[type];
}

// Helper: effective display coordinates based on toggle
interface CoordFunc { (v: LiveVehicle): [number, number]; }
const rawCoord: CoordFunc = (v) => [v.latitude, v.longitude];
const snappedCoord: CoordFunc = (v) =>
  v.snappedLatitude != null && v.snappedLongitude != null
    ? [v.snappedLatitude, v.snappedLongitude]
    : [v.latitude, v.longitude]; // fallback to raw when no geometry

// Interpolating vehicle layer — markers glide smoothly between positions
function VehicleLayer({
  vehicles,
  followId,
  onFollow,
  getCoord,
}: {
  vehicles: LiveVehicle[];
  followId: string | null;
  onFollow: (id: string) => void;
  getCoord: CoordFunc;
}) {
  const map = useMap();
  const markersRef = useRef<Map<string, L.Marker>>(new Map());
  const prevPositions = useRef<Map<string, { lat: number; lng: number; time: number }>>(new Map());
  const followDebounce = useRef(0);

  // Follow vehicle: pan only
  useEffect(() => {
    if (!followId) return;
    const v = vehicles.find((x) => x.vehicleId === followId);
    if (!v) return;
    const [lat, lng] = getCoord(v);
    const now = Date.now();
    if (now - followDebounce.current < 2500) return;
    followDebounce.current = now;
    map.panTo([lat, lng], { animate: true, duration: 0.5 });
  }, [vehicles, followId, map, getCoord]);

  // Interpolate marker positions with requestAnimationFrame
  useEffect(() => {
    const now = Date.now();
    const prev = prevPositions.current;
    const markers = markersRef.current;

    for (const v of vehicles) {
      const [lat, lng] = getCoord(v);
      const oldPos = prev.get(v.vehicleId);
      const marker = markers.get(v.vehicleId);

      if (!marker) {
        // New vehicle: create marker at position
        const m = L.marker([lat, lng], { icon: getIcon(v.vehicleType) });
        m.on('click', () => onFollow(v.vehicleId));
        m.bindPopup('', { closeButton: false });
        m.addTo(map);
        markers.set(v.vehicleId, m);
        prev.set(v.vehicleId, { lat, lng, time: now });
        continue;
      }

      if (oldPos) {
        const dist = Math.abs(oldPos.lat - lat) + Math.abs(oldPos.lng - lng);
        // If position barely changed (< ~10m), skip animation
        if (dist < 0.0001) {
          // Update popup content only
          marker.setPopupContent(buildPopupHtml(v));
          continue;
        }
        // Animate from old position to new
        marker.setLatLng([lat, lng]);
      } else {
        marker.setLatLng([lat, lng]);
      }

      marker.setPopupContent(buildPopupHtml(v));
      prev.set(v.vehicleId, { lat, lng, time: now });
    }

    // Remove stale markers
    for (const [id, marker] of markers) {
      if (!vehicles.find((v) => v.vehicleId === id)) {
        map.removeLayer(marker);
        markers.delete(id);
        prev.delete(id);
      }
    }
  }, [vehicles, map, onFollow, getCoord]);

  return null;
}

function buildPopupHtml(v: LiveVehicle): string {
  return `<div style="font-size:13px;line-height:1.5">
    <strong>${v.vehicleNumber}</strong> (${v.vehicleType})<br/>
    ${v.vehicleName}<br/>
    ${v.pathName ? `Маршрут: ${v.pathName}<br/>` : ''}
    ${v.speedKmh != null ? `Скорость: ${v.speedKmh.toFixed(0)} км/ч<br/>` : ''}
    <span style="color:#6b7280;font-size:11px">Обновлено: ${new Date(v.recordedAt).toLocaleTimeString('ru-RU')}</span>
  </div>`;
}

function StopFollow({ onInteract }: { onInteract: () => void }) {
  const map = useMap();
  useEffect(() => {
    const handler = () => onInteract();
    map.on('dragstart', handler);
    map.on('zoomstart', handler);
    return () => { map.off('dragstart', handler); map.off('zoomstart', handler); };
  }, [map, onInteract]);
  return null;
}

export function LiveMapPage() {
  const [followId, setFollowId] = useState<string | null>(null);
  const [searchNumber, setSearchNumber] = useState('');
  const [vehicleTypeFilter, setVehicleTypeFilter] = useState<string[]>([]);
  const [showSnapped, setShowSnapped] = useState(true);

  const { data: vehicles = [], isLoading, refetch } = useQuery({
    queryKey: ['liveVehicles'],
    queryFn: () => getLiveVehicles({ freshSec: 120 }),
    refetchInterval: 3_000,
  });

  const filtered = vehicles.filter((v) => {
    if (searchNumber && !v.vehicleNumber.toLowerCase().includes(searchNumber.toLowerCase())) return false;
    if (vehicleTypeFilter.length > 0 && !vehicleTypeFilter.includes(v.vehicleType)) return false;
    return true;
  });

  const toggleType = useCallback((type: string) => {
    setVehicleTypeFilter((prev) =>
      prev.includes(type) ? prev.filter((t) => t !== type) : [...prev, type],
    );
  }, []);

  const vehicleTypes = [...new Set(vehicles.map((v) => v.vehicleType))].filter(Boolean);

  const getCoord = showSnapped ? snappedCoord : rawCoord;

  useEffect(ensureSmoothStyle, []);

  return (
    <div style={{ display: 'flex', height: 'calc(100vh - 60px)', position: 'relative' }}>
      <div style={{ flex: 1 }}>
        <MapContainer center={DEFAULT_CENTER} zoom={DEFAULT_ZOOM} style={{ height: '100%', width: '100%' }}>
          <TileLayer
            attribution='&copy; <a href="https://openstreetmap.org/copyright">OpenStreetMap</a>'
            url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
          />
          <StopFollow onInteract={() => setFollowId(null)} />
          <VehicleLayer vehicles={filtered} followId={followId} onFollow={setFollowId} getCoord={getCoord} />
        </MapContainer>
      </div>

      <div style={{
        position: 'absolute', top: '10px', right: '10px', zIndex: 1000,
        background: 'rgba(255,255,255,0.95)', borderRadius: '8px',
        padding: '12px', width: '220px', boxShadow: '0 2px 8px rgba(0,0,0,0.15)',
      }}>
        <div style={{ fontWeight: 600, marginBottom: '8px' }}>Фильтры</div>
        <label style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '13px', marginBottom: '8px' }}>
          <input type="checkbox" checked={showSnapped} onChange={(e) => setShowSnapped(e.target.checked)} />
          По маршруту (snapped)
        </label>
        <input
          type="text" placeholder="Поиск по номеру..." value={searchNumber}
          onChange={(e) => setSearchNumber(e.target.value)}
          style={{ width: '100%', padding: '6px 8px', border: '1px solid #d1d5db', borderRadius: '4px', fontSize: '13px', marginBottom: '8px' }}
        />
        <div style={{ fontSize: '12px', color: '#6b7280', marginBottom: '4px' }}>Тип ТС:</div>
        {vehicleTypes.map((type) => (
          <label key={type} style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '13px', marginBottom: '2px' }}>
            <input
              type="checkbox"
              checked={vehicleTypeFilter.length === 0 || vehicleTypeFilter.includes(type)}
              onChange={() => toggleType(type)}
            />
            {type}
          </label>
        ))}
        <div style={{ marginTop: '10px', fontSize: '12px', color: '#6b7280' }}>
          ТС на карте: {filtered.length} / {vehicles.length}
          {isLoading && <span style={{ marginLeft: '4px' }}>⏳</span>}
        </div>
        <button
          onClick={() => refetch()}
          style={{ marginTop: '8px', width: '100%', padding: '6px', background: '#2563eb', color: 'white', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '13px' }}
        >
          Обновить
        </button>
      </div>
    </div>
  );
}
