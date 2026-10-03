/** Report and takedown categories; mirrors backend `OpenLetterService.REPORT_CATEGORIES`. */
export type ReportCategory =
  'spam' | 'harassment' | 'illegal' | 'child_safety' | 'impersonation' | 'copyright' | 'other';

export const REPORT_CATEGORIES: { value: ReportCategory; label: string }[] = [
  { value: 'spam', label: 'Spam' },
  { value: 'harassment', label: 'Harassment' },
  { value: 'illegal', label: 'Illegal content' },
  { value: 'child_safety', label: 'Child sexual abuse or exploitation' },
  { value: 'impersonation', label: 'Impersonation' },
  { value: 'copyright', label: 'Copyright infringement' },
  { value: 'other', label: 'Something else' },
];

export function categoryLabel(value: string): string {
  if (value === 'withdrawn') {
    return 'Withdrawn by its author';
  }
  return REPORT_CATEGORIES.find((c) => c.value === value)?.label ?? value;
}
