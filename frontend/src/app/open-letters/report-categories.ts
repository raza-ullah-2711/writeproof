/** Report and takedown categories; mirrors backend `OpenLetterService.REPORT_CATEGORIES`. */
export type ReportCategory = 'spam' | 'harassment' | 'illegal' | 'impersonation' | 'other';

export const REPORT_CATEGORIES: { value: ReportCategory; label: string }[] = [
  { value: 'spam', label: 'Spam' },
  { value: 'harassment', label: 'Harassment' },
  { value: 'illegal', label: 'Illegal content' },
  { value: 'impersonation', label: 'Impersonation' },
  { value: 'other', label: 'Something else' },
];

export function categoryLabel(value: string): string {
  return REPORT_CATEGORIES.find((c) => c.value === value)?.label ?? value;
}
