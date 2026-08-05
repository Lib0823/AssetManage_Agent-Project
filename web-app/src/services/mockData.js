// Mock Data for Development

export const mockMarketIndices = {
  domestic: {
    label: '주식(국내)',
    items: [
      { label: '코스피', value: 2345, change: -56, changePercent: -0.2 },
      { label: '코스닥', value: 932, change: -23, changePercent: -0.32 },
      { label: '코스피200', value: 312, change: 5, changePercent: 0.15 },
      { label: 'KRX300', value: 685, change: -12, changePercent: -0.4 }
    ]
  },
  overseas: {
    label: '주식(해외)',
    items: [
      { label: 'S&P500', value: 5832, change: 45, changePercent: 0.78 },
      { label: '나스닥', value: 18432, change: 123, changePercent: 0.67 },
      { label: '다우존스', value: 42840, change: -87, changePercent: -0.20 },
      { label: '니케이225', value: 38915, change: 234, changePercent: 0.61 }
    ]
  },
  coin: {
    label: '코인',
    items: [
      { label: '비트코인', value: 135420000, change: 2340000, changePercent: 1.76 },
      { label: '이더리움', value: 4523000, change: -85000, changePercent: -1.84 },
      { label: '리플', value: 892, change: 23, changePercent: 2.65 },
      { label: '솔라나', value: 287000, change: 12000, changePercent: 4.36 }
    ]
  }
}
