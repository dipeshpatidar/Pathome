const TENANT_PROPERTY_TYPE_LABELS: Record<string, string> = {
  FLAT: 'Flat',
  HOUSE: 'House',
  PLOT: 'Plot',
  LAND: 'Land',
  PENTHOUSE: 'Penthouse',
  STUDIO: 'Studio',
  SERVICED_APARTMENT: 'Serviced apartment'
};

export const tenantPropertyTypeLabel = (propertyType: string | null | undefined): string | null =>
  propertyType ? TENANT_PROPERTY_TYPE_LABELS[propertyType] ?? null : null;
