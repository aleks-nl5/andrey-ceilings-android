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
	LightLinesMeters      float64 `json:"lightLinesMeters"`
	ShadowProfileMeters   float64 `json:"shadowProfileMeters"`
	FloatingCeilingMeters float64 `json:"floatingCeilingMeters"`
	ChandeliersCount      float64 `json:"chandeliersCount"`
	SpotsGX53Count        float64 `json:"spotsGX53Count"`
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
}
type CostBreakdown struct {
	CanvasCost     float64 `json:"canvasCost"`
	ProfileCost    float64 `json:"profileCost"`
	ComplexityCost float64 `json:"complexityCost"`
	OptionsCost    float64 `json:"optionsCost"`
	TotalCost      float64 `json:"totalCost"`
}

func round2(v float64) float64 { return math.Round(v*100) / 100 }
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
		return Geometry{round2(s * s), round2(4 * s), 4}
	}
	if p.Shape == Rectangle || p.Shape == "" {
		l, w = math.Max(.1, l), math.Max(.1, w)
		return Geometry{round2(l * w), round2(2 * (l + w)), 4}
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
		area := math.Max(1, a*b-math.Max(0, e*f))
		return Geometry{round2(area), round2(a + b + c + d + e + f), 6}
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
	area := l * w
	if p.Diagonal1 > 0 {
		tri := func(x, y, diag float64) float64 {
			s := (x + y + diag) / 2
			product := s * (s - x) * (s - y) * (s - diag)
			if product <= 0 {
				return 0
			}
			return math.Sqrt(product)
		}
		candidate := tri(a, b, p.Diagonal1) + tri(c, d, p.Diagonal1)
		if candidate > 0 {
			area = candidate
		}
	}
	corners := 5
	if len(p.CustomSides) >= 4 {
		corners = len(p.CustomSides)
	}
	return Geometry{round2(area), round2(a + b + c + d), corners}
}

// CalculateCeilingCost mirrors calculateCeilingCost() in src/utils/calculator.ts.
func CalculateCeilingCost(p CostInput, prices PriceList) CostBreakdown {
	if len(prices.CanvasPrices) == 0 {
		prices = DefaultPriceList()
	}
	area, perimeter := nonnegative(p.Area), nonnegative(p.Perimeter)
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
		length := math.Max(2, math.Round(perimeter*.35*10)/10)
		complexityCost = math.Round(length * prices.MultilevelPerMeterCost)
	case Curved:
		length := math.Max(2, math.Round(perimeter*.25*10)/10)
		complexityCost = math.Round(length * prices.CurvedPerMeterCost)
	}
	o := p.Options
	optionsCost := nonnegative(o.LightLinesMeters) * prices.LightLinePerMeter
	if profile != ShadowKraab {
		optionsCost += nonnegative(o.ShadowProfileMeters) * prices.ShadowProfilePerMeter
	}
	if profile != FloatingLED {
		optionsCost += nonnegative(o.FloatingCeilingMeters) * prices.FloatingProfilePerMeter
	}
	optionsCost += nonnegative(o.ChandeliersCount) * prices.ChandelierInstallation
	optionsCost += nonnegative(o.SpotsGX53Count) * prices.SpotInstallation
	optionsCost += nonnegative(o.PipePassCount) * prices.PipePass
	optionsCost += nonnegative(o.CurtainNicheMeters) * prices.CurtainNichePerMeter
	optionsCost += nonnegative(o.ExtraCorners) * prices.ExtraCornerCost
	optionsCost += nonnegative(o.HardWallMeters) * prices.HardWallPerMeter
	optionsCost = math.Round(optionsCost)
	return CostBreakdown{canvas, profileCost, complexityCost, optionsCost, canvas + profileCost + complexityCost + optionsCost}
}
