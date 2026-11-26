import { Component, DestroyRef, ElementRef, effect, inject, input, signal, viewChild } from '@angular/core';
import { ThemeStore } from '../core/ui-state';

type ECharts = import('echarts/core').ECharts;
type Options = Record<string, unknown>;

let loader: Promise<typeof import('echarts/core')> | null = null;

/** Loads only the chart types GridIQ uses, on first render, so ECharts stays out of the initial bundle. */
function loadECharts(): Promise<typeof import('echarts/core')> {
  loader ??= Promise.all([
    import('echarts/core'),
    import('echarts/charts'),
    import('echarts/components'),
    import('echarts/renderers'),
  ]).then(([core, charts, components, renderers]) => {
    core.use([
      charts.BarChart, charts.LineChart, charts.PieChart, charts.GaugeChart, charts.HeatmapChart,
      components.GridComponent, components.TooltipComponent, components.LegendComponent,
      components.VisualMapComponent, components.MarkLineComponent, components.MarkAreaComponent,
      components.DatasetComponent, renderers.CanvasRenderer,
    ]);
    return core;
  });
  return loader;
}

export interface ChartPalette {
  ink: string;
  muted: string;
  line: string;
  surface: string;
  brand: string;
  ok: string;
  warn: string;
  bad: string;
  cyan: string;
  violet: string;
  sunken: string;
}

export function palette(): ChartPalette {
  const style = getComputedStyle(document.documentElement);
  const read = (name: string) => style.getPropertyValue(name).trim() || '#888';
  // light-dark() is resolved by the browser when the value is applied, so probe a resolved colour.
  const probe = document.createElement('span');
  document.body.appendChild(probe);
  const resolve = (name: string) => {
    probe.style.color = `var(${name})`;
    return getComputedStyle(probe).color || read(name);
  };
  const colours: ChartPalette = {
    ink: resolve('--ink'), muted: resolve('--faint'), line: resolve('--line'), surface: resolve('--surface'),
    brand: resolve('--brand'), ok: resolve('--ok'), warn: resolve('--warn'), bad: resolve('--bad'),
    cyan: resolve('--cyan'), violet: resolve('--violet'), sunken: resolve('--sunken'),
  };
  probe.remove();
  return colours;
}

/**
 * Responsive ECharts host. Pass a factory so options are rebuilt with the active theme colours whenever the theme or
 * the data changes.
 */
@Component({
  selector: 'app-chart',
  host: { class: 'block' },
  template: `
    <div class="relative">
      <div #host class="w-full" [style.height.px]="height()" role="img" [attr.aria-label]="label()"></div>
      @if (!ready()) {
        <div class="skeleton absolute inset-0"></div>
      }
    </div>
  `,
})
export class EChart {
  readonly options = input.required<(colors: ChartPalette) => Options>();
  readonly height = input(260);
  readonly label = input.required<string>();
  protected readonly ready = signal(false);
  private readonly host = viewChild.required<ElementRef<HTMLDivElement>>('host');
  private readonly theme = inject(ThemeStore);
  private chart: ECharts | null = null;
  private observer: ResizeObserver | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.observer?.disconnect();
      this.chart?.dispose();
    });
    effect(() => {
      const build = this.options();
      this.theme.dark();
      void this.render(build);
    });
  }

  private async render(build: (colors: ChartPalette) => Options): Promise<void> {
    const echarts = await loadECharts();
    const element = this.host().nativeElement;
    if (!this.chart) {
      this.chart = echarts.init(element, undefined, { renderer: 'canvas' });
      if (typeof ResizeObserver !== 'undefined') {
        this.observer = new ResizeObserver(() => this.chart?.resize());
        this.observer.observe(element);
      }
    }
    const colors = palette();
    this.chart.setOption(
      {
        backgroundColor: 'transparent',
        textStyle: { fontFamily: 'Inter, system-ui, sans-serif', color: colors.muted },
        animationDuration: 500,
        ...build(colors),
      },
      { notMerge: true },
    );
    this.ready.set(true);
  }
}

export function axisStyle(colors: ChartPalette) {
  return {
    axisLine: { lineStyle: { color: colors.line } },
    axisTick: { show: false },
    axisLabel: { color: colors.muted },
    splitLine: { lineStyle: { color: colors.line, type: 'dashed' as const } },
  };
}

/**
 * Tooltips render as canvas rich text. ECharts' HTML tooltips build their rows with inline style attributes, which the
 * strict Content-Security-Policy (style-src 'self') blocks, collapsing the spacing and colour markers. Custom
 * formatters therefore use "\n" for line breaks, not HTML.
 */
export function tooltipStyle(colors: ChartPalette) {
  return {
    renderMode: 'richText' as const,
    backgroundColor: colors.surface,
    borderColor: colors.line,
    textStyle: { color: colors.ink },
    confine: true,
  };
}
