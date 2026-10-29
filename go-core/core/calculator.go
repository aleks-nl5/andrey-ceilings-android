package core

import "math"

type RoomShape string

const (
	Rectangle RoomShape = "rectangle"
	Square    RoomShape = "square"
	LShaped   RoomShape = "l_shaped"
	Custom    RoomShape = "custom"
)

type MaterialType string

const (
	PVCMatte     MaterialType = "pvc_matte"
	PVCGlossy    MaterialType = "pvc_glossy"
	PVCSatin     MaterialType = "pvc_satin"
	Fabric       MaterialType = "fabric"
	FireproofKM2 MaterialType = "fireproof_km2"
	Acoustic     MaterialType = "acoustic"
)

type ProfileType string

const (
	StandardAlu ProfileType = "standard_alu"
	PVCProfile  ProfileType = "pvc"
	ShadowKraab ProfileType = "shadow_kraab"
	FloatingLED ProfileType = "floating_led"
	SlottTracks ProfileType = "slott_tracks"
)

type Complexity string

const (
	Straight   Complexity = "straight"
	Multilevel Complexity = "multilevel"
	Curved     Complexity = "curved"
)

type RoomOptions struct {
	LightLineMeters      float64 `json:"lightLineMeters"`
	ShadowProfileMeters   float64 `json:"shadowProfileMeters"`
	FloatingCeilingMeters float64 `json:"floatingCeilingMeters"`
	ChandeliersCount      float64 `json:"chandeliersCount"`
	SpotCount        float64 `json:"spotCount"`
	PipePassCount         float64 `json:"pipePassCount"`
	CurtainNicheMeters    float64 `json:"curtainNicheMeters"`
	ExtraCorners          float64 `json:"extraCorners"`
	HardWallMeters        float64 `json:"hardWallMeters"`
}
type PriceList struct {
	CanvasPrices            map[MaterialType]float64 `json:"canvasPrices"`
	ProfilePrices           map[ProfileType]float64  `json:"profilePrices"`
	MultilevelPerMeterCost  float64                  `json:"multilevelPerMeterCost"`
	CurvedPerMeterCost      float64                  `json:"curvedPerMeterCost"`
	LightLinePerMeter       float64                  `json:"lightLinePerMeter"`
	ShadowProfilePerMeter   float64                  `json:"shadowProfilePerMeter"`
	FloatingProfilePerMeter float64                  `json:"floatingProfilePerMeter"`
	ChandelierInstallation  float64                  `json:"chandelierInstallation"`
	SpotInstallation        float64                  `json:"spotInstallation"`
	PipePass                float64                  `json:"pipePass"`
	CurtainNichePerMeter    float64                  `json:"curtainNichePerMeter"`
	ExtraCornerCost         float64                  `json:"extraCornerCost"`
	HardWallPerMeter        float64                  `json:"hardWallPerMeter"`
}
type GeometryInput struct {
	Shape       RoomShape `json:"shape"`
	Length      float64   `json:"length"`
	Width       float64   `json:"width"`
	SideA       float64   `json:"sideA"`
	SideB       float64   `json:"sideB"`
	SideC       float64   `json:"sideC"`
	SideD       float64   `json:"sideD"`
	SideE       float64   `json:"sideE"`
	SideF       float64   `json:"sideF"`
	Diagonal1   float64   `json:"diagonal1"`
	CustomSides []float64 `json:"customSides"`
	PrecisionStepMeters float64 `json:"precisionStepMeters,omitempty"`
}
type Geometry struct {
	Area         float64 `json:"area"`
	Perimeter    float64 `json:"perimeter"`
	CornersCount int     `json:"cornersCount"`
}
type CostInput struct {
	Area         float64      `json:"area"`
	Perimeter    float64      `json:"perimeter"`
	MaterialType MaterialType `json:"materialType"`
	Complexity   Complexity   `json:"complexity"`
	ProfileType  ProfileType  `json:"profileType"`
	Options      RoomOptions  `json:"options"`
	PrecisionStepMeters float64 `json:"precisionStepMeters,omitempty"`
}
type CostBreakdown struct {
	CanvasCost     float64 `json:"canvasCost"`
	ProfileCost    float64 `json:"profileCost"`
	ComplexityCost float64 `json:"complexityCost"`
	OptionsCost    float64 `json:"optionsCost"`
	TotalCost      float64 `json:"totalCost"`
}

func round2(v float64) float64 { return math.Round(v*100) / 100 }

// Selected measurement resolution changes dimensions and geometry outputs.
// Zero preserves pre-existing Go behavior for legacy tests and requests.
func measured(v, step float64) float64 {
    if step == 0 { return v }
    return math.Round(v/step)*step
}
func geometryResult(area, perimeter float64, corners int, step float64) Geometry {
    if step == 0 { return Geometry{round2(area), round2(perimeter), corners} }
    return Geometry{measured(area, step*step), measured(perimeter, step), corners}
}
func nonnegative(v float64) float64 {
	if v < 0 {
		return 0
	}
	return v
}

func DefaultPriceList() PriceList {
	return PriceList{
		CanvasPrices: map[MaterialType]float64{
			PVCMatte: 290, PVCGlossy: 310, PVCSatin: 310, Fabric: 890, FireproofKM2: 480, Acoustic: 980,
		},
		ProfilePrices: map[ProfileType]float64{
			StandardAlu: 130, PVCProfile: 90, ShadowKraab: 380, FloatingLED: 420, SlottTracks: 550,
		},
		MultilevelPerMeterCost: 650, CurvedPerMeterCost: 350, LightLinePerMeter: 850,
		ShadowProfilePerMeter: 380, FloatingProfilePerMeter: 420, ChandelierInstallation: 350,
		SpotInstallation: 220, PipePass: 180, CurtainNichePerMeter: 450, ExtraCornerCost: 90,
		HardWallPerMeter: 220,
	}
}

// CalculateRoomGeometry mirrors the source web calculator's default geometry rules.
func CalculateRoomGeometry(p GeometryInput) Geometry {
    step := p.PrecisionStepMeters
    if step != .1 && step != .01 && step != .005 && step != .001 { step = 0 }
	l, w := p.Length, p.Width
	if l <= 0 {
		l = 4
	}
	if w <= 0 {
		w = 3
	}
	if p.Shape == Square {
		s := p.Length
		if s <= 0 {
			s = p.Width
		}
		if s <= 0 {
			s = 3
		}
		s = math.Max(.1, s)
		return geometryResult(measured(s, step)*measured(s, step), 4*measured(s, step), 4, step)
	}
	if p.Shape == Rectangle || p.Shape == "" {
		l, w = math.Max(.1, l), math.Max(.1, w)
		return geometryResult(measured(l, step)*measured(w, step), 2*(measured(l, step)+measured(w, step)), 4, step)
	}
	if p.Shape == LShaped {
		a := p.SideA
		if a <= 0 {
			a = p.Length
		}
		if a <= 0 {
			a = 5
		}
		b := p.SideB
		if b <= 0 {
			b = p.Width
		}
		if b <= 0 {
			b = 4
		}
		c := p.SideC
		if c <= 0 {
			c = a * .5
		}
		d := p.SideD
		if d <= 0 {
			d = b * .5
		}
		e := p.SideE
		if e <= 0 {
			e = math.Max(.1, a-c)
		}
		f := p.SideF
		if f <= 0 {
			f = math.Max(.1, b-d)
		}
		area := math.Max(1, measured(a, step)*measured(b, step)-math.Max(0, measured(e, step)*measured(f, step)))
		return geometryResult(area, measured(a, step)+measured(b, step)+measured(c, step)+measured(d, step)+measured(e, step)+measured(f, step), 6, step)
	}
	a := p.SideA
	if a <= 0 {
		a = l
	}
	b := p.SideB
	if b <= 0 {
		b = w
	}
	c := p.SideC
	if c <= 0 {
		c = l
	}
	d := p.SideD
	if d <= 0 {
		d = w
	}
	area := measured(l, step) * measured(w, step)
	if p.Diagonal1 > 0 {
		tri := func(x, y, diag float64) float64 {
			s := (x + y + diag) / 2
			product := s * (s - x) * (s - y) * (s - diag)
			if product <= 0 {
				return 0
			}
			return math.Sqrt(product)
		}
		candidate := tri(measured(a, step), measured(b, step), measured(p.Diagonal1, step)) + tri(measured(c, step), measured(d, step), measured(p.Diagonal1, step))
		if candidate > 0 {
			area = candidate
		}
	}
	corners := 5
	if len(p.CustomSides) >= 4 {
		corners = len(p.CustomSides)
	}
	return geometryResult(area, measured(a, step)+measured(b, step)+measured(c, step)+measured(d, step), corners, step)
}

// CalculateCeilingCost mirrors calculateCeilingCost() in src/utils/calculator.ts.
func CalculateCeilingCost(p CostInput, prices PriceList) CostBreakdown {
	if len(prices.CanvasPrices) == 0 {
		prices = DefaultPriceList()
	}
	area, perimeter := nonnegative(p.Area), nonnegative(p.Perimeter)
    step := p.PrecisionStepMeters
    if step != .1 && step != .01 && step != .005 && step != .001 { step = 0 }
    linear := func(v float64) float64 { return measured(nonnegative(v), step) }
    complexityLength := func(v float64) float64 {
        if step == 0 { return math.Round(v*10)/10 }
        return measured(v, step)
    }
	material := p.MaterialType
	if _, ok := prices.CanvasPrices[material]; !ok {
		material = PVCMatte
	}
	profile := p.ProfileType
	if _, ok := prices.ProfilePrices[profile]; !ok {
		profile = StandardAlu
	}
	canvas := math.Round(area * prices.CanvasPrices[material])
	profileCost := math.Round(perimeter * prices.ProfilePrices[profile])
	complexityCost := 0.0
	switch p.Complexity {
	case Multilevel:
		length := math.Max(2, complexityLength(perimeter*.35))
		complexityCost = math.Round(length * prices.MultilevelPerMeterCost)
	case Curved:
		length := math.Max(2, complexityLength(perimeter*.25))
		complexityCost = math.Round(length * prices.CurvedPerMeterCost)
	}
	o := p.Options
	optionsCost := linear(o.LightLineMeters) * prices.LightLinePerMeter
	if profile != ShadowKraab {
		optionsCost += linear(o.ShadowProfileMeters) * prices.ShadowProfilePerMeter
	}
	if profile != FloatingLED {
		optionsCost += linear(o.FloatingCeilingMeters) * prices.FloatingProfilePerMeter
	}
	optionsCost += nonnegative(o.ChandeliersCount) * prices.ChandelierInstallation
	optionsCost += nonnegative(o.SpotCount) * prices.SpotInstallation
	optionsCost += nonnegative(o.PipePassCount) * prices.PipePass
	optionsCost += linear(o.CurtainNicheMeters) * prices.CurtainNichePerMeter
	optionsCost += nonnegative(o.ExtraCorners) * prices.ExtraCornerCost
	optionsCost += linear(o.HardWallMeters) * prices.HardWallPerMeter
	optionsCost = math.Round(optionsCost)
	return CostBreakdown{canvas, profileCost, complexityCost, optionsCost, canvas + profileCost + complexityCost + optionsCost}
}
