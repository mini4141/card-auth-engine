// Builds a CloudWatch metric-widget definition (latency per strategy) from `aws cloudwatch list-metrics` output.
// usage: node cloudwatch-widget.mjs <list-metrics.json> <startIso>
import fs from 'node:fs';

const [file, start] = process.argv.slice(2);
const metrics = JSON.parse(fs.readFileSync(file, 'utf8')).Metrics ?? [];
const series = metrics
  .filter(m => m.Dimensions.some(d => d.Name === 'decision' && d.Value === 'APPROVED'))
  .map(m => {
    const dims = m.Dimensions.flatMap(d => [d.Name, d.Value]);
    const strategy = m.Dimensions.find(d => d.Name === 'strategy')?.Value ?? '?';
    return [m.Namespace, m.MetricName, ...dims, { label: strategy, stat: 'Maximum' }];
  });
if (series.length === 0) process.exit(1);
console.log(JSON.stringify({
  width: 1000, height: 400, start, end: new Date().toISOString(), view: 'timeSeries', stacked: false, period: 60,
  title: 'auth.latency.max by strategy (card-auth-engine namespace, ms)',
  metrics: series,
}));
