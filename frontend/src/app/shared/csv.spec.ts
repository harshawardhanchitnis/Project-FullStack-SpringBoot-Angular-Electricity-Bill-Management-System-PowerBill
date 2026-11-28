import { csvCell, toCsv } from './csv';

describe('CSV export', () => {
  it('quotes cells with commas, quotes and line breaks', () => {
    expect(csvCell('Pune, MH')).toBe('"Pune, MH"');
    expect(csvCell('say "hi"')).toBe('"say ""hi"""');
    expect(csvCell('line\nbreak')).toBe('"line\nbreak"');
    expect(csvCell(null)).toBe('');
    expect(csvCell(42.5)).toBe('42.5');
    expect(csvCell(true)).toBe('true');
  });

  it('neutralises spreadsheet formulas in text cells but keeps negative numbers', () => {
    expect(csvCell('=HYPERLINK("x")')).toBe('"\'=HYPERLINK(""x"")"');
    expect(csvCell('+91 98765')).toBe("'+91 98765");
    expect(csvCell('@SUM(A1)')).toBe("'@SUM(A1)");
    expect(csvCell(-120)).toBe('-120');
  });

  it('writes a header row and one CRLF-separated line per row', () => {
    const csv = toCsv([{ bill: 'GIQ-1', amount: 1200 }, { bill: 'GIQ-2', amount: 0 }], [
      { header: 'Bill', value: (row) => row.bill },
      { header: 'Amount', value: (row) => row.amount },
    ]);
    expect(csv).toBe('Bill,Amount\r\nGIQ-1,1200\r\nGIQ-2,0');
  });
});
