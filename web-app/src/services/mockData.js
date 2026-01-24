// Mock Data for Development

export const mockUser = {
  id: 'test_id',
  name: 'test_name',
  phone: '010-1111-2222',
  birth: '2024-06-10',
  broker: {
    name: '한국 투자 증권',
    accountNumber: '01151321-02315',
    appKey: '****',
    appSecret: '**************'
  }
}

export const mockAssetSummary = {
  totalAsset: 100000000,
  totalChange: -12000,
  changePercent: -0.012,
  updatedAt: '2024-08-25T14:24:20',
  breakdown: {
    cash: {
      amount: 30000000,
      change: -83000,
      changePercent: -0.27
    },
    stocks: {
      amount: 70000000,
      change: 138000,
      changePercent: 0.20
    },
    bonds: {
      amount: 0,
      change: 0,
      changePercent: 0
    },
    coins: {
      amount: 0,
      change: 0,
      changePercent: 0
    }
  }
}

export const mockStockDetail = {
  domestic: {
    estimatedAsset: 234521000,
    d2Deposit: 234521000,
    totalProfit: 3521000,
    profitPercent: 37,
    totalPurchase: 234521000,
    totalValuation: 234521000
  },
  overseas: {
    estimatedAsset: 234521000,
    d2Deposit: 234521000,
    totalProfit: 3521000,
    profitPercent: 37,
    totalPurchase: 234521000,
    totalValuation: 234521000
  }
}

export const mockStocks = [
  {
    symbol: 'AMZN',
    name: '아마존',
    nameEn: 'Amazon',
    currentPrice: 23400,
    purchasePrice: 140000,
    profit: 20000,
    profitPercent: 34,
    avgPrice: 20000,
    quantity: 124,
    logo: 'https://logo.clearbit.com/amazon.com'
  },
  {
    symbol: 'APPL',
    name: '애플',
    nameEn: 'Apple',
    currentPrice: 23400,
    purchasePrice: 140000,
    profit: 20000,
    profitPercent: 34,
    avgPrice: 20000,
    quantity: 124,
    logo: 'https://logo.clearbit.com/apple.com'
  }
]

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

export const mockExchangeRates = [
  { currency: 'USD', country: '미국', rate: 1342, change: 7, changePercent: 0.12 },
  { currency: 'JPY', country: '일본', rate: 1342, change: -3, changePercent: -0.07 }
]

export const mockTopNews = [
  {
    id: 1,
    title: '테슬라 로보택시 출시 예정',
    source: '블룸버그',
    date: '2024-10-24 08:10',
    image: 'https://picsum.photos/200/150?random=1'
  },
  {
    id: 2,
    title: 'Title',
    description: 'Description',
    date: '9:41 AM'
  },
  {
    id: 3,
    title: 'Title',
    description: 'Description',
    date: '9:41 AM'
  }
]

export const mockAiRecommendations = [
  { title: 'Title', description: 'Description', time: '9:41 AM', image: null },
  { title: 'Title', description: 'Description', time: '9:41 AM', image: null },
  { title: 'Title', description: 'Description', time: '9:41 AM', image: null },
  { title: 'Title', description: 'Description', time: '9:41 AM', image: null }
]

export const mockCompanyInfo = {
  symbol: 'AMZN',
  name: '아마존',
  nameEn: 'Amazon.com, Inc.',
  address: 'Seattle, WA 98109-5210 410',
  website: 'http://www.aboutamazon.com',
  sector: '경기 소비재',
  employees: 1525000,
  shares: 10515000000,
  currency: 'USD',
  marketCap: 2189010,
  dividend: '2022-12-28 (현금배당)',
  description: '동사는 전세계를 대상으로, 다양한 품목을 제공하는 온라인 웹사이트...',
  scores: {
    per: 70,
    pbr: 70,
    roe: 70,
    eps: 70
  }
}

export const mockTradingOrders = {
  pending: [
    { type: 'sell', symbol: 'AMZN', name: '아마존', price: 4262000, currency: '$' },
    { type: 'buy', symbol: 'AMZN', name: '아마존', price: 4262000, currency: '$' }
  ],
  reserved: [
    { type: 'sell', symbol: 'AMZN', name: '아마존', price: 734000, currency: '$' },
    { type: 'buy', symbol: 'AMZN', name: '아마존', price: 2532000, currency: '$' }
  ]
}

export const mockTradingHistory = [
  { type: 'sell', symbol: 'AMZN', name: '아마존', amount: 4262000, currency: '$' },
  { type: 'buy', symbol: 'AMZN', name: '아마존', amount: 12320, currency: '$' },
  { type: 'dividend', label: '원화', amount: 234520, currency: '$' }
]

export const mockTransactionSummary = {
  buy: { amount: 193234133, symbol: 'TSLA', name: '테슬라' },
  sell: { amount: 193234133, symbol: 'AMZN', name: '아마존' },
  other: { amount: 1828234, label: '입출금 / 배당' }
}

export const mockNewsDetail = {
  id: 1,
  title: '테슬라 로보택시 출시 예정',
  source: '블룸버그',
  date: '2024-10-24 18:34',
  tags: ['#테슬라', '#로보택시', '#자율주행', '#출시', '#사이버캡'],
  image: 'https://picsum.photos/300/200?random=1',
  content: `테슬라가 개발한 로보택시 '사이버캡'입니다.

완전 자율주행 기술이 적용돼 내부엔 운전대도 페달도 없습니다. 세계 1위 전기차 기업 테슬라가 로보택시 사업을 공식화 하면서 자율주행 차량에 대한 업계 주목도도 한층 높아졌습니다.

현대자동차는 자체 기술개발에 더해 최근 모빌리티 파운드리, 차량 위탁 생산이라는 신사업에 뛰어들었습니다.

글로벌 자율주행 기술 선두기업, 구글의 자회사 웨이모와 협력해 맞춤형 차량 생산에 나서기로 한 겁니다.

이렇게 위탁 생산해낸 차량은 자율주행 택시 서비스 '웨이모 원'에 투입할 계획으로, 미래 자율주행차 시장 선점을 위한 발걸음을 재촉하고 있습니다.`,
  relatedNews: [
    { id: 2, title: '테슬라 사이버트럭 판매 실적 31,456억원...', image: 'https://picsum.photos/100/100?random=2' },
    { id: 3, title: '유럽 자율주행 전진 협업 통과 매섭...', image: 'https://picsum.photos/100/100?random=3' },
    { id: 4, title: '웨이모 자율주행 택시 성행', image: 'https://picsum.photos/100/100?random=4' }
  ]
}

export const mockBotStatus = {
  enabled: true,
  totalInvestment: 1000000,
  totalValuation: 1234292,
  profitPercent: 23.2,
  currentStock: {
    symbol: 'AMZN',
    name: '아마존',
    currentPrice: 23400,
    purchasePrice: 140000,
    profit: 20000,
    profitPercent: 34,
    avgPrice: 20000,
    quantity: 124
  }
}

export const mockBotAnalysis = {
  symbol: 'AMZN',
  name: 'Amazon',
  points: [
    {
      title: '지속적인 매출 성장',
      content: '클라우드 서비스(AWS), 전자상거래, 디지털 광고 등 다양한 사업 부문에서 꾸준한 성장세를 보이고 있습니다.'
    },
    {
      title: '혁신과 확장성',
      content: '인공지능, 물류 자동화, 로봇 공학 등 미래 산업에서의 혁신을 지속적으로 추진하며, 장기적으로 더 큰 성장 잠재력이 있습니다.'
    }
  ]
}

export const mockSettings = {
  assetOrder: ['stocks_overseas', 'stocks_domestic', 'coins', 'bonds'],
  darkMode: true,
  autoLogin: true,
  notifications: {
    stocks: {
      news: true,
      trading: true
    },
    coins: {
      news: true,
      trading: true
    }
  }
}

export const mockSearchResults = [
  { symbol: 'TSLA', name: '테슬라', isFavorite: true },
  { symbol: 'AMZN', name: '아마존', isFavorite: false },
  { symbol: 'AAPL', name: '애플', isFavorite: true },
  { symbol: 'GOOG', name: '구글', isFavorite: true },
  { symbol: 'MSFT', name: '마이크로소프트', isFavorite: false },
  { symbol: 'NVDA', name: '엔비디아', isFavorite: false },
  { symbol: 'META', name: '메타', isFavorite: false },
  { symbol: 'NFLX', name: '넷플릭스', isFavorite: false }
]
