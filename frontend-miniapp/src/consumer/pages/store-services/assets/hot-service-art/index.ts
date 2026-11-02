import PET_GROOMING from './PET_GROOMING.png'
import DOG_WALKING from './DOG_WALKING.png'
import PET_BOARDING from './PET_BOARDING.png'
import PET_TRAINING from './PET_TRAINING.png'
import HOME_FEEDING from './HOME_FEEDING.png'
import VET_ASSISTANT from './VET_ASSISTANT.png'

// D-02 fallback card art for the six categories present in Figma 97:7825. Categories without
// dedicated art keep the neutral placeholder; no fake illustration is synthesized.
export const hotServiceArtFallback: Readonly<Record<string, string>> = {
  PET_GROOMING,
  DOG_WALKING,
  PET_BOARDING,
  PET_TRAINING,
  HOME_FEEDING,
  VET_ASSISTANT,
}

export function hotServiceArtFor(categoryCode: string): string | undefined {
  return hotServiceArtFallback[categoryCode]
}
