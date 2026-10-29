package mobileapi

import (
	"encoding/json"
	"testing"
)

func TestCalculateJSON(t *testing.T) {
	got := CalculateJSON(`{"geometry":{"shape":"rectangle","length":5.4,"width":3.8},"cost":{"materialType":"pvc_matte","profileType":"standard_alu","complexity":"straight"}}`)
	var response struct {
		Geometry struct {
			Area      float64 `json:"area"`
			Perimeter float64 `json:"perimeter"`
		} `json:"geometry"`
		Cost struct {
			TotalCost float64 `json:"totalCost"`
		} `json:"cost"`
		Error string `json:"error"`
	}
	if err := json.Unmarshal([]byte(got), &response); err != nil {
		t.Fatalf("invalid JSON: %v", err)
	}
	if response.Error != "" {
		t.Fatalf("unexpected error: %s", response.Error)
	}
	if response.Geometry.Area != 20.52 || response.Geometry.Perimeter != 18.4 {
		t.Fatalf("unexpected geometry: %+v", response.Geometry)
	}
	if response.Cost.TotalCost != 8343 {
		t.Fatalf("unexpected total: %v", response.Cost.TotalCost)
	}
}

func TestCalculateJSONInvalidInput(t *testing.T) {
	got := CalculateJSON("not json")
	var response map[string]any
	if err := json.Unmarshal([]byte(got), &response); err != nil {
		t.Fatalf("invalid JSON error response: %v", err)
	}
	if response["error"] == nil {
		t.Fatal("expected error field")
	}
}

func TestCalculateJSONUsesPriceOverridesAndOptions(t *testing.T) {
	got := CalculateJSON(`{"geometry":{"shape":"rectangle","length":5.4,"width":3.8},"cost":{"materialType":"pvc_matte","profileType":"standard_alu","complexity":"straight","options":{"lightLineMeters":2}},"prices":{"canvasPrices":{"pvc_matte":100},"profilePrices":{"standard_alu":10},"lightLinePerMeter":50}}`)
	var response struct {
		Cost struct {
			CanvasCost  float64 `json:"canvasCost"`
			ProfileCost float64 `json:"profileCost"`
			OptionsCost float64 `json:"optionsCost"`
			TotalCost   float64 `json:"totalCost"`
		} `json:"cost"`
		Error string `json:"error"`
	}
	if err := json.Unmarshal([]byte(got), &response); err != nil {
		t.Fatalf("invalid JSON: %v", err)
	}
	if response.Error != "" {
		t.Fatalf("unexpected error: %s", response.Error)
	}
	if response.Cost.CanvasCost != 2052 || response.Cost.ProfileCost != 184 || response.Cost.OptionsCost != 100 || response.Cost.TotalCost != 2336 {
		t.Fatalf("price overrides not applied: %+v", response.Cost)
	}
}
