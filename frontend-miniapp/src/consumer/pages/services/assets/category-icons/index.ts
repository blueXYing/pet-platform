import PET_GROOMING from './PET_GROOMING.png'
import DOG_WALKING from './DOG_WALKING.png'
import PET_BOARDING from './PET_BOARDING.png'
import PET_TRAINING from './PET_TRAINING.png'
import HOME_FEEDING from './HOME_FEEDING.png'
import VET_ASSISTANT from './VET_ASSISTANT.png'
import SMALL_PET_BOARDING from './SMALL_PET_BOARDING.png'
import EXOTIC_HOME_FEEDING from './EXOTIC_HOME_FEEDING.png'
import EXOTIC_HEALTH_CHECK from './EXOTIC_HEALTH_CHECK.png'
import PLANT_CARE from './PLANT_CARE.png'
import PLANT_BOARDING from './PLANT_BOARDING.png'

// D-01 fallback assets: the backend `iconUrl` stays the first source (scheme A); when operations
// have not filled it yet, the real Figma category glyph is used instead of a neutral placeholder.
// These are bundled defaults, not a new upload pipeline; ops can override any icon via iconUrl.
export const categoryIconFallback: Readonly<Record<string, string>> = {
  PET_GROOMING,
  DOG_WALKING,
  PET_BOARDING,
  PET_TRAINING,
  HOME_FEEDING,
  VET_ASSISTANT,
  SMALL_PET_BOARDING,
  EXOTIC_HOME_FEEDING,
  EXOTIC_HEALTH_CHECK,
  PLANT_CARE,
  PLANT_BOARDING,
}

export function categoryIconFor(categoryCode: string): string | undefined {
  return categoryIconFallback[categoryCode]
}
