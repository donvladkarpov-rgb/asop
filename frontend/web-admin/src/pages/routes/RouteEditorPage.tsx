import { useState, useEffect, useRef, useCallback } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { MapContainer, TileLayer, useMap } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { getPaths, updatePath, getRoutes } from '../../api/routes';
import { getRegions } from '../../api/reference';
import { useGlobalFilter } from '../../contexts/GlobalFilterContext';
import type { Path, Route } from '../../types/route';
import type { Region } from '../../types/reference';

const DEFAULT_CENTER: L.LatLngExpression = [44.95, 34.11];
const DEFAULT_ZOOM = 14;

// Marker icon fix for Leaflet
const markerIcon = L.icon({
  iconUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon.png',
  shadowUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-shadow.png',
  iconSize: [25, 41],
  iconAnchor: [12, 41],
  popupAnchor: [1, -34],
});

// --- Great-circle helpers for densification ---
const EARTH_R = 6_371_000;

function toRad(v: number): number {
  return (v * Math.PI) / 180;
}

/** Distance between two [lat, lon] points in meters (haversine). */
function distanceMeters(a: [number, number], b: [number, number]): number {
  const dLat = toRad(b[0] - a[0]);
  const dLon = toRad(b[1] - a[1]);
  const lat1 = toRad(a[0]);
  const lat2 = toRad(b[0]);
  const h =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
  return 2 * EARTH_R * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** Point at given fraction t in [0,1] along the great-circle from a to b. */
function interpolate(a: [number, number], b: [number, number], t: number): [number, number] {
  return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];
}

/**
 * Resample the polyline so consecutive points are spaced ~`stepMeters` apart.
 * Keeps the original vertices; inserts intermediate points along each segment.
 */
export function densifyPolyline(points: [number, number][], stepMeters = 40): [number, number][] {
  if (points.length < 2) return points.slice();
  const out: [number, number][] = [points[0]];
  for (let i = 0; i < points.length - 1; i++) {
    const a = points[i];
    const b = points[i + 1];
    const segLen = distanceMeters(a, b);
    if (segLen <= 0) {
      out.push(b);
      continue;
    }
    const n = Math.max(1, Math.floor(segLen / stepMeters));
    for (let k = 1; k < n; k++) {
      out.push(interpolate(a, b, k / n));
    }
    out.push(b);
  }
  return out;
}

// Fixed spacing for saved geometry (per user, 40 m).
const DENSE_STEP_METERS = 40;

// Dynamic polyline drawer: click adds a vertex, mouse previews the segment to the cursor,
// Enter / double-click finishes the line (disables preview).
function RouteDrawer({
  initialCoords,
  onChange,
}: {
  initialCoords: [number, number][];
  onChange: (coords: [number, number][]) => void;
}) {
  const map = useMap();
  const polylineRef = useRef<L.Polyline | null>(null);
  const activeLineRef = useRef<L.Polyline | null>(null);
  const markersRef = useRef<L.Marker[]>([]);
  const pointsRef = useRef<[number, number][]>(initialCoords);
  // Drawing is always open after load so the user can keep adding vertices.
  // Finishing the line is an explicit action (Enter / "Готово" button), not auto-set on load.
  const finishedRef = useRef<boolean>(false);
  const lastRef = useRef<L.LatLng | null>(null);

  const redraw = useCallback((points: [number, number][]) => {
    const mapNow = map;
    if (polylineRef.current) polylineRef.current.remove();
    polylineRef.current = L.polyline(points, { color: '#2563eb', weight: 4 }).addTo(mapNow);

    markersRef.current.forEach((m) => m.remove());
    markersRef.current = [];
    points.forEach((p, idx) => {
      const m = L.marker(p, { icon: markerIcon, draggable: true })
        .addTo(mapNow)
        .bindTooltip(String(idx + 1), { permanent: false });
      m.on('drag', () => {
        const latlng = m.getLatLng();
        pointsRef.current[idx] = [latlng.lat, latlng.lng];
        if (!finishedRef.current) lastRef.current = latlng;
        updatePreview();
        redraw(pointsRef.current);
        onChange(pointsRef.current);
      });
      m.on('click', (e) => {
        if (e.originalEvent.ctrlKey) {
          pointsRef.current = pointsRef.current.filter((_, i) => i !== idx);
          redraw(pointsRef.current);
          onChange(pointsRef.current);
        }
      });
      markersRef.current.push(m);
    });
  }, [map, onChange]);

  // Helper to draw the dashed "to cursor" preview segment.
  const activePreviewRefUpdate = useCallback(() => {
    if (!activeLineRef.current) {
      activeLineRef.current = L.polyline([], {
        color: '#f59e0b',
        weight: 3,
        dashArray: '6 6',
      }).addTo(map);
    }
  }, [map]);

  const updatePreview = useCallback(() => {
    activePreviewRefUpdate();
    const pts = pointsRef.current;
    const lastPoint = lastRef.current;
    if (finishedRef.current || pts.length === 0 || !lastPoint) {
      activeLineRef.current?.setLatLngs([]);
      return;
    }
    // Preview from last vertex to cursor.
    activeLineRef.current?.setLatLngs([[pts[pts.length - 1][0], pts[pts.length - 1][1]], [lastPoint.lat, lastPoint.lng]]);
  }, [activePreviewRefUpdate]);

  const finishLine = useCallback(() => {
    finishedRef.current = true;
    activeLineRef.current?.setLatLngs([]);
    redraw(pointsRef.current);
  }, [redraw]);

  // Initial draw
  useEffect(() => {
    redraw(initialCoords);
    // Always leave drawing open so an existing loaded line can be extended.
    finishedRef.current = false;
    if (initialCoords.length > 0) {
      lastRef.current = null;
    }
    return () => {
      polylineRef.current?.remove();
      activeLineRef.current?.remove();
      markersRef.current.forEach((m) => m.remove());
    };
  }, [initialCoords, redraw]);

  // Click adds a vertex; mousemove updates preview; Enter finishes.
  useEffect(() => {
    const clickHandler = (e: L.LeafletMouseEvent) => {
      if (finishedRef.current) return;
      const pt: [number, number] = [e.latlng.lat, e.latlng.lng];
      pointsRef.current = [...pointsRef.current, pt];
      lastRef.current = e.latlng;
      redraw(pointsRef.current);
      onChange(pointsRef.current);
      updatePreview();
    };
    const moveHandler = (e: L.LeafletMouseEvent) => {
      lastRef.current = e.latlng;
      updatePreview();
    };
    const keyHandler = (e: KeyboardEvent) => {
      if (e.key === 'Enter') finishLine();
    };
    map.on('click', clickHandler);
    map.on('mousemove', moveHandler);
    window.addEventListener('keydown', keyHandler);
    return () => {
      map.off('click', clickHandler);
      map.off('mousemove', moveHandler);
      window.removeEventListener('keydown', keyHandler);
    };
  }, [map, redraw, onChange, finishLine, updatePreview]);

  // Clear button support
  const clearPoints = useCallback(() => {
    pointsRef.current = [];
    lastRef.current = null;
    finishedRef.current = false;
    redraw([]);
    onChange([]);
  }, [redraw, onChange]);

  return (
    <div style={{ position: 'absolute', top: '10px', left: '10px', zIndex: 1000, display: 'flex', gap: 6, flexDirection: 'column', alignItems: 'flex-start' }}>
      <div style={{ display: 'flex', gap: 6 }}>
        <button type="button" className="btn-secondary btn-sm" onClick={clearPoints}>Очистить</button>
        <button type="button" className="btn-secondary btn-sm" onClick={finishLine}>Готово (линия)</button>
      </div>
      <span style={{ background: 'rgba(255,255,255,0.9)', padding: '4px 8px', borderRadius: '4px', fontSize: '12px', maxWidth: 320 }}>
        Клик — добавить точку. Пунктир — предпросмотр от последней точки к курсору. Перетащить точку — изменить.
        Enter или «Готово» — завершить линию. Ctrl+клик по точке — удалить.
      </span>
    </div>
  );
}

export function RouteEditorPage() {
  const qc = useQueryClient();
  const { regionId: globalRegionId } = useGlobalFilter();
  const [pathId, setPathId] = useState<string>('');
  const [coords, setCoords] = useState<[number, number][]>([]);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const { data: paths } = useQuery<Path[]>({ queryKey: ['paths', globalRegionId], queryFn: () => getPaths({ regionId: globalRegionId || undefined }) });
  const { data: routes } = useQuery<Route[]>({ queryKey: ['routes', globalRegionId], queryFn: () => getRoutes({ regionId: globalRegionId || undefined }) });
  const { data: regions } = useQuery<Region[]>({ queryKey: ['regions'], queryFn: getRegions });

  const selectedPath = paths?.find((p) => p.id === pathId);

  // Load geometry when a path is selected
  useEffect(() => {
    if (!selectedPath) { setCoords([]); return; }
    setCoords(parseRouteObject(selectedPath.routeObject));
  }, [selectedPath]);

  const updateMut = useMutation({
    mutationFn: ({ id, data }: { id: string; data: Partial<Path> }) => updatePath(id, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['paths'] }); setSaved(true); setError(null); setTimeout(() => setSaved(false), 3000); },
    onError: (e: any) => setError(e?.response?.data?.error || e?.message || 'Ошибка сохранения'),
  });

  // Invalidate route geometry cache on the server so snapped coords refresh
  const save = () => {
    if (!pathId || !selectedPath) return;
    if (!coords.length) {
      setError('Добавьте хотя бы одну точку на маршрут');
      return;
    }
    // Разбиваем нарисованную (разреженную) ломаную на точки с фиксированным шагом (~40 м),
    // чтобы snap-to-route привязывался к плотной линии маршрута.
    const dense = densifyPolyline(coords, DENSE_STEP_METERS);
    const routeObject = JSON.stringify({
      type: 'LineString',
      coordinates: dense.map(([lat, lon]) => [lon, lat]),
    });
    updateMut.mutate({
      id: pathId,
      data: {
        routeId: selectedPath.routeId,
        pathName: selectedPath.pathName,
        regionId: selectedPath.regionId || globalRegionId || '',
        routeObject,
        benefitPolicy: selectedPath.benefitPolicy || '',
        startStopId: selectedPath.startStopId || '',
        endStopId: selectedPath.endStopId || '',
        pathStartDate: selectedPath.pathStartDate || null,
        pathEndDate: selectedPath.pathEndDate || null,
        description: selectedPath.description || '',
      },
    });
  };

  return (
    <div>
      <div className="page-header">
        <h1>Редактор маршрута (Пути)</h1>
      </div>

      <div className="form-card" style={{ marginBottom: 12 }}>
        <div className="form-grid">
          <div className="form-group">
            <label>Путь</label>
            <select value={pathId} onChange={(e) => setPathId(e.target.value)}>
              <option value="">Выберите путь</option>
              {paths?.map((p) => (
                <option key={p.id} value={p.id}>
                  {routes?.find((r: any) => r.id === p.routeId)?.routeNumber || ''} — {p.pathName} ({regions?.find((r: any) => r.id === p.regionId)?.municipalDivision || ''})
                </option>
              ))}
            </select>
          </div>
          {selectedPath && (
            <>
              <div className="form-group">
                <label>Описание пути</label>
                <div style={{ paddingTop: 6, fontSize: 13 }}>{selectedPath.pathName}</div>
              </div>
              <div className="form-group">
                <label>Вершин (кликов)</label>
                <div style={{ paddingTop: 6, fontSize: 13 }}>{coords.length}</div>
              </div>
              {coords.length >= 2 && (
                <div className="form-group">
                  <label>Точек в базе (шаг 40 м)</label>
                  <div style={{ paddingTop: 6, fontSize: 13 }}>
                    {densifyPolyline(coords, DENSE_STEP_METERS).length}
                  </div>
                </div>
              )}
            </>
          )}
        </div>
        {error && <div className="form-error">{error}</div>}
        {saved && <div className="form-success" style={{ color: '#16a34a', marginTop: 8 }}>Сохранено ✓</div>}
      </div>

      <div style={{ height: 'calc(100vh - 260px)', minHeight: 400, position: 'relative', borderRadius: 8, overflow: 'hidden', border: '1px solid #e5e7eb' }}>
        {pathId ? (
          <MapContainer center={DEFAULT_CENTER} zoom={DEFAULT_ZOOM} style={{ height: '100%', width: '100%' }}>
            <TileLayer
              attribution='&copy; <a href="https://openstreetmap.org/copyright">OpenStreetMap</a>'
              url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
            />
            <RouteDrawer initialCoords={coords} onChange={(c) => { setCoords(c); setSaved(false); }} />
          </MapContainer>
        ) : (
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%', color: '#6b7280' }}>
            Выберите путь для редактирования
          </div>
        )}
      </div>

      {pathId && (
        <div style={{ marginTop: 12 }}>
          <button className="btn-primary" onClick={save} disabled={updateMut.isPending}>
            {updateMut.isPending ? 'Сохранение...' : 'Сохранить маршрут'}
          </button>
        </div>
      )}
    </div>
  );
}

function parseRouteObject(routeObject?: string | null): [number, number][] {
  if (!routeObject) return [];
  try {
    const obj = JSON.parse(routeObject);
    if (!obj || !Array.isArray(obj.coordinates)) return [];
    // GeoJSON coordinates are [lon, lat]
    return obj.coordinates.map((c: number[]) => [c[1], c[0]]);
  } catch {
    return [];
  }
}
