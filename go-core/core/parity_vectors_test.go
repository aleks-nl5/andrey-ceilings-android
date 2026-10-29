package core

import "testing"

// These reference vectors are kept in lockstep with KmpCeilingCalculatorParityTest.
// Go remains the production engine until the platform and UI parity gates pass.
func TestKmpMigrationGeometryReferenceVectors(t *testing.T) {
	vectors := []struct {
		name string
		in   GeometryInput
		want Geometry
	}{
		{"rectangle", GeometryInput{Shape: Rectangle, Length: 5.4, Width: 3.8}, Geometry{20.52, 18.4, 4}},
		{"rectangle_default", GeometryInput{Shape: Rectangle, Length: -1}, Geometry{12, 14, 4}},
		{"square_default", GeometryInput{Shape: Square}, Geometry{9, 12, 4}},
		{"square_width", GeometryInput{Shape: Square, Width: 2.5}, Geometry{6.25, 10, 4}},
		{"l_explicit", GeometryInput{Shape: LShaped, SideA: 5, SideB: 4, SideC: 2, SideD: 2, SideE: 3, SideF: 2}, Geometry{14, 18, 6}},
		{"l_default_sides", GeometryInput{Shape: LShaped, SideA: 5, SideB: 4}, Geometry{15, 18, 6}},
		{"custom_diagonal", GeometryInput{Shape: Custom, Length: 3, Width: 4, Diagonal1: 5}, Geometry{12, 14, 5}},
		{"custom_corners", GeometryInput{Shape: Custom, Length: 3, Width: 4, Diagonal1: 5, CustomSides: []float64{1, 1, 1, 1, 1, 1}}, Geometry{12, 14, 6}},
		{"round_half", GeometryInput{Shape: Rectangle, Length: .05, Width: .1}, Geometry{.01, .3, 4}},
	}
	for _, tc := range vectors {
		t.Run(tc.name, func(t *testing.T) {
			got := CalculateRoomGeometry(tc.in)
			if got != tc.want {
				t.Fatalf("Go reference geometry: got %+v, want %+v", got, tc.want)
			}
		})
	}
}

func TestKmpMigrationCostReferenceVectors(t *testing.T) {
	defaults := DefaultPriceList()
	partial := PriceList{
		CanvasPrices: map[MaterialType]float64{PVCMatte: 100},
		ProfilePrices: map[ProfileType]float64{StandardAlu: 10},
		LightLinePerMeter: 50,
	}
	vectors := []struct {
		name   string
		input  CostInput
		prices PriceList
		want   CostBreakdown
	}{
		{"all_default_extras", CostInput{Area: 20.52, Perimeter: 18.4, MaterialType: PVCMatte, ProfileType: StandardAlu,
			Options: RoomOptions{ChandeliersCount: 1, SpotsGX53Count: 4, PipePassCount: 1}}, defaults,
			CostBreakdown{5951, 2392, 0, 1410, 9753}},
		{"partial_override", CostInput{Area: 20.52, Perimeter: 18.4, MaterialType: PVCMatte, ProfileType: StandardAlu,
			Options: RoomOptions{LightLinesMeters: 2}}, partial, CostBreakdown{2052, 184, 0, 100, 2336}},
		{"multilevel", CostInput{Area: 10, Perimeter: 18.4, MaterialType: PVCMatte, ProfileType: StandardAlu, Complexity: Multilevel},
			defaults, CostBreakdown{2900, 2392, 4160, 0, 9452}},
		{"curved", CostInput{Area: 10, Perimeter: 18.4, MaterialType: PVCMatte, ProfileType: StandardAlu, Complexity: Curved},
			defaults, CostBreakdown{2900, 2392, 1610, 0, 6902}},
		{"shadow_no_double_charge", CostInput{Area: 10, Perimeter: 12, MaterialType: PVCMatte,
			ProfileType: ShadowKraab, Options: RoomOptions{ShadowProfileMeters: 12}}, defaults,
			CostBreakdown{2900, 4560, 0, 0, 7460}},
		{"floating_no_double_charge", CostInput{Area: 10, Perimeter: 12, MaterialType: PVCMatte,
			ProfileType: FloatingLED, Options: RoomOptions{FloatingCeilingMeters: 12}}, defaults,
			CostBreakdown{2900, 5040, 0, 0, 7940}},
		{"negative", CostInput{Area: -10, Perimeter: -3, Options: RoomOptions{SpotsGX53Count: -2}},
			defaults, CostBreakdown{}},
		{"fractional_options", CostInput{Area: 0, Perimeter: 0, Options: RoomOptions{
			LightLinesMeters: .5, SpotsGX53Count: 1.5, ExtraCorners: .5}}, defaults,
			CostBreakdown{0, 0, 0, 800, 800}},
		{"round_positive_half", CostInput{Area: .005}, PriceList{
			CanvasPrices: map[MaterialType]float64{PVCMatte: 100},
			ProfilePrices: map[ProfileType]float64{StandardAlu: 0}}, CostBreakdown{1, 0, 0, 0, 1}},
		{"round_negative_half", CostInput{Area: .5}, PriceList{
			CanvasPrices: map[MaterialType]float64{PVCMatte: -1},
			ProfilePrices: map[ProfileType]float64{StandardAlu: 0}}, CostBreakdown{-1, 0, 0, 0, -1}},
		{"unknown_material", CostInput{Area: 10, MaterialType: "unknown"}, defaults,
			CostBreakdown{2900, 0, 0, 0, 2900}},
		{"empty_canvas_resets_prices", CostInput{Area: 10}, PriceList{
			CanvasPrices: map[MaterialType]float64{}, ProfilePrices: map[ProfileType]float64{StandardAlu: 1}},
			CostBreakdown{2900, 0, 0, 0, 2900}},
	}
	for _, tc := range vectors {
		t.Run(tc.name, func(t *testing.T) {
			got := CalculateCeilingCost(tc.input, tc.prices)
			if got != tc.want {
				t.Fatalf("Go reference cost: got %+v, want %+v", got, tc.want)
			}
		})
	}
}
