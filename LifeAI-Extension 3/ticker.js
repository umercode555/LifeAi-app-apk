// Worker timers are not throttled when the dashboard tab is in the background.
onmessage = (e) => {
  const d = e.data;
  if (d.t === 'sleep') setTimeout(() => postMessage({ id: d.id }), d.ms);
  if (d.t === 'tick') setInterval(() => postMessage({ tick: 1 }), d.ms);
};
