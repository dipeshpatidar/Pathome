export function exactBhk(choice: string, exactBedrooms?: number): string | null {
  if (choice === '1RK' || choice === '1BHK' || choice === '2BHK' || choice === '3BHK') return choice;
  if (choice === '4+' && Number.isInteger(exactBedrooms) && exactBedrooms! >= 4 && exactBedrooms! <= 99) {
    return `${exactBedrooms}BHK`;
  }
  return null;
}

export function bhkChoice(saved: string | null): string | null {
  if (saved && /^(?:[4-9]|[1-9][0-9])BHK$/.test(saved)) return '4+';
  return saved;
}

export function pricingReady(monthlyRent: number | null, deposit: number | null): boolean {
  return monthlyRent !== null && Number.isFinite(monthlyRent) && monthlyRent > 0
    && deposit !== null && Number.isFinite(deposit) && deposit >= 0;
}
